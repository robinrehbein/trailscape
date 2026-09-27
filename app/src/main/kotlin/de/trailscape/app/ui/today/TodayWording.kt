package de.trailscape.app.ui.today

import androidx.annotation.StringRes
import de.trailscape.app.R
import de.trailscape.app.i18n.UiText
import de.trailscape.app.ui.localOfEpochMs
import de.trailscape.core.HrvAssessment
import de.trailscape.core.HrvStatus
import de.trailscape.core.ReadinessBand
import de.trailscape.core.RecoveryFlag
import de.trailscape.core.RestingHrAssessment
import de.trailscape.core.RideInfo
import de.trailscape.core.RouteTarget
import de.trailscape.core.SessionIntensity
import de.trailscape.core.SleepAssessment
import de.trailscape.core.TodayRoute
import de.trailscape.core.TrainingSession
import de.trailscape.core.TsbBand
import de.trailscape.core.classifyTsb
import de.trailscape.core.formatGoalDuration
import de.trailscape.core.formatRoundHours
import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.formatMonthName
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
 * ## Sprache
 * Die Saetze stehen in `res/values[-en]/strings_today.xml`. Die Funktionen
 * liefern deshalb [UiText] statt fertiger Strings: welcher Satz mit welchen
 * Zahlen — aufgeloest wird erst in der Anzeige, in der Sprache der
 * Oberflaeche. Jede Tagesart hat ihren eigenen ganzen Satz; zusammengesetzt
 * werden hoechstens ganze Saetze (Readiness-Vorsatz + Schlagzeile) und
 * sprachneutrale Zahlen. Wochentage und Dauer kommen ueber [CoreTexts].
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
 *  2a. Die erste Runde ohne gefahrene Tour ([TodayRoute.firstRound]) ist
 *     **locker** — sie ist bewusst ein ruhiger Einstieg, keine „normale
 *     Runde". Erst nach den Ruhetag-Pruefungen: Ein Plan-Ruhetag bleibt auch
 *     ohne Touren ein Ruhetag.
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
    if (route.firstRound) return TodayEffort.LOCKER
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

/**
 * Was die App heute zum Fahren anbietet: eine Runde samt der Angabe, ob es die
 * Trainingsrunde oder das ruhigere Angebot eines Ruhetags ist.
 *
 * @param restDay `true`, wenn [target] die lockere Ruhetagsrunde ist
 *   ([de.trailscape.core.restDayRideTarget]) und nicht die Tagesrunde.
 * @param firstRound `true`, wenn [target] die erste Runde ohne gefahrene Tour
 *   ist ([TodayRoute.firstRound]). Dann startet „Runde bauen" die Suche
 *   sofort, statt nur das Panel zu oeffnen.
 */
data class TodayOffer(val target: RouteTarget, val restDay: Boolean, val firstRound: Boolean = false)

/**
 * Welche Runde „Heute", die Karte und der Losfahren-Dialog anbieten.
 *
 * ## Warum eine einzige Funktion
 * Die drei Stellen rechneten bisher je fuer sich: „Heute" kannte den
 * Plan-Ruhetag ([todayEffort] mit `planRestDay`), die Karte und der Dialog
 * nahmen nur [TodayRoute.target] — und das ist an einem planfreien Tag die
 * normale Tagesempfehlung. So stand oben „Heute ist Ruhetag" und auf der
 * Karte „★ Heute 21 km". Jetzt entscheidet die Tagesart ([TodayEffort]), und
 * alle drei lesen dasselbe Ergebnis.
 *
 * ## Die Regeln
 *  * **Zieltag** — kein Angebot; die Strecke des Events steht schon.
 *  * **Ruhetag** (aus dem Plan oder aus der Tagesform) — die lockere
 *    [restDayRide], ausdruecklich als solche markiert. Fahren bleibt erlaubt,
 *    nur nicht die geplante Trainingsrunde: Wer am Ruhetag aufs Rad will,
 *    bekommt das, was einem Ruhetag am wenigsten schadet.
 *  * **Sonst** — die Tagesrunde aus [decideTodayRoute][de.trailscape.core.decideTodayRoute].
 */
fun offeredTarget(route: TodayRoute, effort: TodayEffort, restDayRide: RouteTarget): TodayOffer? =
    when (effort) {
        TodayEffort.ZIELTAG -> null
        TodayEffort.RUHETAG -> TodayOffer(restDayRide, restDay = true)
        else -> route.target?.let { TodayOffer(it, restDay = false, firstRound = route.firstRound) }
    }

/**
 * Beschriftung des Knopfs auf der Karte: „Heute 45 km" bzw. am Ruhetag
 * „Locker · 16 km".
 *
 * Der Knopf bekommt nur die halbe Blattbreite (auf 360-dp-Geraeten rund
 * 110 dp fuer Text). Ein Satz wie „Ruhetag – locker rollen?" wurde dort hart
 * abgeschnitten; den Ruhetag tragen schon Spa-Symbol und graue Flaeche, der
 * ausfuehrliche Satz steht im Losfahren-Dialog. Die Kilometer bleiben, damit
 * man vor dem Tippen weiss, wie lang die Runde wird.
 *
 * Gerundet wie in „Heute" und im Dialog: Die Tagesrunde ist Stunden × Tempo
 * und hat fast immer Nachkommastellen — abgeschnitten stand hier „21 km",
 * dort „22 km".
 *
 * Die Karte liest den fertigen String ueber [offerChipLabel] (`TodayCards.kt`).
 */
fun offerChipText(offer: TodayOffer): UiText {
    val km = offer.target.distanceKm.roundToInt()
    return UiText.Res(
        if (offer.restDay) R.string.today_offer_chip_rest_label else R.string.today_offer_chip_label,
        listOf(km),
    )
}

/**
 * Beschriftung des Knopfs in der Hero-Karte von „Heute". Am Ruhetag dasselbe
 * „Locker rollen" wie im Losfahren-Dialog, hier mit Kilometern, weil der Knopf
 * die volle Breite hat. Bei der ersten Runde ebenfalls mit Kilometern: Der
 * Knopf baut sie sofort, man soll vorher sehen, wie lang sie wird.
 */
fun offerButtonLabel(offer: TodayOffer): UiText = when {
    offer.restDay -> UiText.Res(R.string.today_offer_rest_action, listOf(offer.target.distanceKm.roundToInt()))
    offer.firstRound ->
        UiText.Res(R.string.today_offer_first_round_action, listOf(offer.target.distanceKm.roundToInt()))
    else -> UiText.Res(R.string.today_offer_action)
}

/**
 * Der dezente Satz im Losfahren-Dialog — am Ruhetag mit derselben Schlagzeile,
 * die auch „Heute" traegt ([restHeadline], ohne den Readiness-Vorsatz):
 * „Heute ist Ruhetag." am Plan-Ruhetag, „Heute lieber Pause statt Training."
 * an einem Tagesform-Ruhetag mit Planeinheit. Ganze Kilometer wie in „Heute":
 * Vorher stand hier „45,0 km", oben „45 km" — dieselbe Zahl, zwei
 * Schreibweisen.
 */
fun offerHint(
    offer: TodayOffer,
    restHeadline: UiText = UiText.Res(R.string.today_rest_headline_plan),
): UiText {
    val km = offer.target.distanceKm.roundToInt()
    return if (offer.restDay) {
        UiText.Res(R.string.today_offer_rest_hint, listOf(restHeadline, km))
    } else {
        UiText.Res(R.string.today_offer_hint, listOf(km))
    }
}

/** Beschriftung des Bau-Knopfs im Losfahren-Dialog — am Ruhetag wie in „Heute". */
fun offerDialogAction(offer: TodayOffer): UiText =
    UiText.Res(if (offer.restDay) R.string.today_offer_dialog_rest_action else R.string.today_offer_dialog_action)

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
fun readinessWord(band: ReadinessBand): UiText = UiText.Res(
    when (band) {
        ReadinessBand.HART -> R.string.today_readiness_word_hard
        ReadinessBand.NORMAL -> R.string.today_readiness_word_normal
        ReadinessBand.LOCKER -> R.string.today_readiness_word_easy
        ReadinessBand.RUHE -> R.string.today_readiness_word_rest
    },
)

/** Der erste Halbsatz der Schlagzeile — nur mit Gesamtwert. */
private fun readinessLead(band: ReadinessBand): UiText = UiText.Res(
    when (band) {
        ReadinessBand.HART -> R.string.today_headline_lead_hard
        ReadinessBand.NORMAL -> R.string.today_headline_lead_normal
        ReadinessBand.LOCKER -> R.string.today_headline_lead_easy
        ReadinessBand.RUHE -> R.string.today_headline_lead_rest
    },
)

/**
 * Die Ruhetag-Schlagzeile ohne Readiness-Vorsatz — geteilt von [todayHeadline]
 * und dem Losfahren-Dialog ([offerHint]), damit beide denselben Grund nennen:
 * Plan-Ruhetag, Tagesform statt Planeinheit oder Tagesform ohne Plan.
 */
fun restHeadline(route: TodayRoute, planRestDay: Boolean): UiText = UiText.Res(
    when {
        route.session != null -> R.string.today_rest_headline_skipped
        planRestDay -> R.string.today_rest_headline_plan
        else -> R.string.today_rest_headline_readiness
    },
)

/**
 * Die Schlagzeile der Hero-Karte: „Gut erholt. Heute eine lockere Runde."
 *
 * Ruhetag, Zieltag und Herunterstufung sagen das **ausdruecklich** — eine
 * Seite, die still 55 statt 90 km anbietet, laesst die Nutzerin raten, ob sie
 * sich verlesen hat.
 *
 * Die erste Runde ohne gefahrene Tour sagt, was sie ist: ein Anfang, keine
 * Tagesform-Auskunft („Für den Anfang: eine ruhige Runde.").
 *
 * Jede Tagesart hat ihren eigenen ganzen Satz statt „Heute" + Satzglied —
 * im Englischen stehen Artikel und Wortstellung anders, ein eingesetztes
 * „eine lockere Runde" liesse sich nicht sauber uebersetzen.
 *
 * @param band das Readiness-Band, oder `null` ohne Gesamtwert (dann faellt
 *   der erste Halbsatz weg — die Empfehlung kommt dann aus dem Plan).
 */
fun todayHeadline(
    effort: TodayEffort,
    route: TodayRoute,
    band: ReadinessBand?,
    planRestDay: Boolean,
): UiText {
    val body = when (effort) {
        TodayEffort.ZIELTAG -> UiText.Res(R.string.today_headline_event)
        TodayEffort.RUHETAG -> restHeadline(route, planRestDay)

        TodayEffort.LANG -> UiText.Res(R.string.today_headline_long)
        TodayEffort.HART -> UiText.Res(R.string.today_headline_hard)
        // Uebrig bleiben LOCKER und MITTEL.
        TodayEffort.LOCKER, TodayEffort.MITTEL -> {
            val easy = effort == TodayEffort.LOCKER
            UiText.Res(
                when {
                    route.firstRound -> R.string.today_headline_first_round
                    route.downgraded && easy -> R.string.today_headline_downgraded_easy
                    route.downgraded -> R.string.today_headline_downgraded_moderate
                    easy -> R.string.today_headline_easy
                    else -> R.string.today_headline_moderate
                },
            )
        }
    }
    return if (band != null) UiText.Res(R.string.today_headline_with_lead, listOf(readinessLead(band), body)) else body
}

/**
 * Der eine Satz unter der Schlagzeile: was konkret zu fahren ist, in Worten
 * statt Zonen. „45 km ruhig fahren, so dass du dich noch unterhalten kannst."
 */
fun todaySentence(effort: TodayEffort, route: TodayRoute): UiText {
    val km = route.target?.distanceKm?.roundToInt()
    val planned = route.plannedKm
    val kmText: UiText = when {
        km == null -> UiText.Plain("")
        route.downgraded && planned != null && planned != km ->
            UiText.Res(R.string.today_sentence_km_instead, listOf(km, planned))
        else -> UiText.Res(R.string.today_sentence_km, listOf(km))
    }
    return when (effort) {
        TodayEffort.LOCKER -> if (route.target?.intensity == SessionIntensity.LOCKER) {
            UiText.Res(R.string.today_sentence_easy_spin, listOf(kmText))
        } else {
            UiText.Res(R.string.today_sentence_easy, listOf(kmText))
        }

        TodayEffort.MITTEL -> UiText.Res(R.string.today_sentence_moderate, listOf(kmText))
        TodayEffort.HART -> UiText.Res(R.string.today_sentence_hard, listOf(kmText))
        TodayEffort.LANG -> UiText.Res(R.string.today_sentence_long, listOf(kmText))
        // Unter dem Satz steht am Ruhetag der Knopf „Locker rollen" (siehe
        // [offeredTarget]); der Satz sagt deshalb, wofuer er da ist, statt
        // mit „Spaziergang" gegen ihn zu reden.
        TodayEffort.RUHETAG -> route.session?.let {
            UiText.Res(R.string.today_sentence_rest_skipped, listOf(it.targetKm))
        } ?: UiText.Res(R.string.today_sentence_rest)

        TodayEffort.ZIELTAG ->
            UiText.Res(R.string.today_sentence_event, listOf(route.session?.targetKm ?: km ?: 0))
    }
}

/** Titel des „Warum?"-Blatts: „Warum eine lockere Runde?" */
fun whyTitle(effort: TodayEffort, route: TodayRoute): UiText = UiText.Res(
    when {
        effort == TodayEffort.ZIELTAG -> R.string.today_why_title_event
        effort == TodayEffort.RUHETAG -> R.string.today_why_title_rest
        route.firstRound -> R.string.today_why_title_first_round
        route.downgraded -> R.string.today_why_title_downgraded
        else -> when (effort) {
            TodayEffort.LOCKER -> R.string.today_why_title_easy
            TodayEffort.MITTEL -> R.string.today_why_title_moderate
            TodayEffort.HART -> R.string.today_why_title_hard
            TodayEffort.LANG -> R.string.today_why_title_long
            // Oben schon beantwortet; nur fuer die Vollstaendigkeit des `when`.
            TodayEffort.RUHETAG -> R.string.today_why_title_rest
            TodayEffort.ZIELTAG -> R.string.today_why_title_event
        }
    },
)

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
    val label: UiText,
    val word: UiText,
    val tone: SignalTone,
    val sentence: UiText,
)

/** Kurzform fuer eine Zeile, deren Name und Wort Ressourcen ohne Argumente sind. */
private fun signal(@StringRes label: Int, @StringRes word: Int, tone: SignalTone, sentence: UiText) =
    WhySignal(UiText.Res(label), UiText.Res(word), tone, sentence)

/** Satz fuer ein fehlendes Signal — die Uhr liefert nichts oder noch zu wenig. */
private fun missingSignal(@StringRes label: Int, collectedDays: Int): WhySignal = if (collectedDays <= 0) {
    signal(
        label,
        R.string.today_signal_no_data_word,
        SignalTone.NEUTRAL,
        UiText.Res(R.string.today_signal_no_data_body),
    )
} else {
    signal(
        label,
        R.string.today_signal_collecting_word,
        SignalTone.NEUTRAL,
        UiText.Res(R.string.today_signal_collecting_body),
    )
}

/** „7 h 40 min" — in beiden Sprachen gleich (nur Einheitenzeichen). */
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
    val label = R.string.today_signal_sleep_label
    val last = sleep.lastNightH
    val dev = sleep.deviationH
    if (!sleep.available || last == null || dev == null) return missingSignal(label, sleep.validNights)
    val (word, tone) = when (sleep.flag) {
        RecoveryFlag.GRUEN, RecoveryFlag.UNBEKANNT -> R.string.today_signal_sleep_good_word to SignalTone.GUT
        RecoveryFlag.GELB -> R.string.today_signal_sleep_bit_short_word to SignalTone.ACHTUNG
        RecoveryFlag.ORANGE -> R.string.today_signal_sleep_short_word to SignalTone.ACHTUNG
        RecoveryFlag.ROT -> R.string.today_signal_sleep_very_short_word to SignalTone.WARNUNG
    }
    val duration = formatHoursMinutes(last)
    val sentence = when {
        abs(dev) < 0.25 -> UiText.Res(R.string.today_signal_sleep_same_body, listOf(duration))
        dev > 0 -> UiText.Res(R.string.today_signal_sleep_more_body, listOf(duration))
        else -> UiText.Res(R.string.today_signal_sleep_less_body, listOf(duration, formatHoursMinutes(-dev)))
    }
    return signal(label, word, tone, sentence)
}

/** Ruhepuls gegen den eigenen Normalwert. */
fun restingHrSignal(restingHr: RestingHrAssessment): WhySignal {
    val label = R.string.today_signal_resting_hr_label
    val current = restingHr.current
    val delta = restingHr.deltaBpm
    if (!restingHr.available || current == null || delta == null) {
        return missingSignal(label, restingHr.baselineDays)
    }
    val (word, tone) = when (restingHr.flag) {
        RecoveryFlag.GRUEN, RecoveryFlag.UNBEKANNT -> R.string.today_signal_resting_hr_normal_word to SignalTone.GUT
        RecoveryFlag.GELB -> R.string.today_signal_resting_hr_slightly_raised_word to SignalTone.ACHTUNG
        RecoveryFlag.ORANGE -> R.string.today_signal_resting_hr_raised_word to SignalTone.ACHTUNG
        RecoveryFlag.ROT -> R.string.today_signal_resting_hr_clearly_raised_word to SignalTone.WARNUNG
    }
    val bpm = current.roundToInt()
    val sentence = when {
        abs(delta) < 1.5 -> UiText.Res(R.string.today_signal_resting_hr_same_body, listOf(bpm))
        delta > 0 -> UiText.Res(R.string.today_signal_resting_hr_higher_body, listOf(bpm, delta.roundToInt()))
        else -> UiText.Res(R.string.today_signal_resting_hr_lower_body, listOf(bpm))
    }
    return signal(label, word, tone, sentence)
}

/** HRV gegen den eigenen Normalbereich — im Blatt heisst sie „Erholung (HRV)". */
fun hrvSignal(hrv: HrvAssessment): WhySignal {
    val label = R.string.today_signal_hrv_label
    val current = hrv.currentRmssd
    val low = hrv.bandLowRmssd
    val high = hrv.bandHighRmssd
    if (!hrv.available || current == null || low == null || high == null) {
        return missingSignal(label, hrv.historyDays)
    }
    // Zahl, Strich und „ms" sind in beiden Sprachen gleich.
    val range = "${low.roundToInt()}–${high.roundToInt()} ms"
    val value = "${current.roundToInt()} ms"
    fun sentence(@StringRes id: Int) = UiText.Res(id, listOf(value, range))
    return when (hrv.status) {
        HrvStatus.IM_BAND, HrvStatus.UNBEKANNT -> signal(
            label,
            R.string.today_signal_hrv_normal_word,
            SignalTone.GUT,
            sentence(R.string.today_signal_hrv_in_range_body),
        )

        HrvStatus.UEBER_BAND -> signal(
            label,
            R.string.today_signal_hrv_good_word,
            SignalTone.GUT,
            sentence(R.string.today_signal_hrv_above_body),
        )

        HrvStatus.NIEDRIG -> if (hrv.flag == RecoveryFlag.GELB) {
            signal(
                label,
                R.string.today_signal_hrv_bit_low_word,
                SignalTone.ACHTUNG,
                sentence(R.string.today_signal_hrv_slightly_below_body),
            )
        } else {
            signal(
                label,
                R.string.today_signal_hrv_low_word,
                if (hrv.flag == RecoveryFlag.ROT) SignalTone.WARNUNG else SignalTone.ACHTUNG,
                sentence(R.string.today_signal_hrv_clearly_below_body),
            )
        }

        HrvStatus.SAETTIGUNG -> signal(
            label,
            R.string.today_signal_hrv_unusual_word,
            SignalTone.ACHTUNG,
            UiText.Res(R.string.today_signal_hrv_saturation_body, listOf(value)),
        )
    }
}

/**
 * Die Belastung der letzten Wochen — der Formwert, ohne ihn beim Namen zu
 * nennen. `null` heisst: noch keine Fitnesskurve.
 */
fun loadSignal(tsb: Double?): WhySignal {
    val label = R.string.today_signal_load_label
    if (tsb == null) {
        return signal(
            label,
            R.string.today_signal_no_data_word,
            SignalTone.NEUTRAL,
            UiText.Res(R.string.today_signal_load_no_data_body),
        )
    }
    val (word, tone, sentence) = when (classifyTsb(tsb)) {
        TsbBand.SEHR_FRISCH -> Triple(
            R.string.today_signal_load_very_fresh_word,
            SignalTone.GUT,
            R.string.today_signal_load_very_fresh_body,
        )

        TsbBand.FORMSPITZE -> Triple(
            R.string.today_signal_load_fresh_word,
            SignalTone.GUT,
            R.string.today_signal_load_fresh_body,
        )

        TsbBand.NEUTRAL -> Triple(
            R.string.today_signal_load_balanced_word,
            SignalTone.GUT,
            R.string.today_signal_load_balanced_body,
        )

        TsbBand.PRODUKTIV -> Triple(
            R.string.today_signal_load_tired_word,
            SignalTone.ACHTUNG,
            R.string.today_signal_load_tired_body,
        )

        TsbBand.UEBERLASTUNG -> Triple(
            R.string.today_signal_load_very_tired_word,
            SignalTone.WARNUNG,
            R.string.today_signal_load_very_tired_body,
        )
    }
    return signal(label, word, tone, UiText.Res(sentence))
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
 * Bei der ersten Runde ([TodayRoute.firstRound]) steht hier, warum sie so
 * ruhig ist und dass sich das mit jeder Fahrt aendert. Der allgemeine Satz
 * „Ohne Trainingsziel …" entfaellt dann — er behauptete „deine letzten
 * Fahrten", die es noch nicht gibt.
 *
 * [TodayRoute.note] kommt fertig aus `:core`, in der Sprache von [texts]
 * gerechnet; [texts] liefert ausserdem Wochentag und Dauer.
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
    texts: CoreTexts,
): List<UiText> = buildList {
    route.note?.let { add(UiText.Plain(it)) }
    if (route.firstRound && effort != TodayEffort.RUHETAG) {
        val hours = route.target?.durationH
        add(
            if (hours != null) {
                UiText.Res(R.string.today_why_first_round_hours_body, listOf(formatRoundHours(hours, texts)))
            } else {
                UiText.Res(R.string.today_why_first_round_body)
            },
        )
        add(UiText.Res(R.string.today_why_first_round_adapts_body))
    }
    if (effort == TodayEffort.RUHETAG && planRestDay && route.session == null) {
        add(UiText.Res(R.string.today_why_plan_rest_day_body))
    }
    upcoming?.let {
        // Ein ganzer Satz je Art: Im Englischen steht der Wochentag hinten
        // („… is coming up on Saturday."), ein eingesetztes Satzglied passte nicht.
        val head = UiText.Res(
            when (it.kind) {
                UpcomingKind.EVENT -> R.string.today_why_upcoming_event_body
                UpcomingKind.HARD -> R.string.today_why_upcoming_hard_body
                UpcomingKind.LONG -> R.string.today_why_upcoming_long_body
                UpcomingKind.LONGER -> R.string.today_why_upcoming_longer_body
            },
            listOf(texts.format.weekdayLong(PLAN_DAY_CODES[it.dayIndex]), it.km),
        )
        add(
            if (effort == TodayEffort.LOCKER || effort == TodayEffort.MITTEL) {
                UiText.Res(R.string.today_why_upcoming_save_energy_body, listOf(head))
            } else {
                head
            },
        )
    }
    if (deloadRecommended) {
        add(UiText.Res(R.string.today_why_deload_body))
    }
    if (!hasPlan && isEmpty()) {
        add(UiText.Res(R.string.today_why_no_plan_body))
    }
}

/** Welche Art gewichtiger Einheit als Naechstes ansteht. */
enum class UpcomingKind { EVENT, HARD, LONG, LONGER }

/**
 * „Samstag steht die lange Fahrt mit 80 km an." — die Bausteine dazu.
 *
 * @param dayIndex 0 = Montag … 6 = Sonntag; den Namen setzt erst [whyNote]
 *   in der Sprache der Oberflaeche ein.
 */
data class UpcomingSession(val dayIndex: Int, val kind: UpcomingKind, val km: Int)

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
    val kind = when {
        session.isEvent -> UpcomingKind.EVENT
        session.intensity == SessionIntensity.HART -> UpcomingKind.HARD
        isLongestOfWeek(session, weekSessions) -> UpcomingKind.LONG
        else -> UpcomingKind.LONGER
    }
    return UpcomingSession(index, kind, session.targetKm)
}

// ---------------------------------------------------------------------------
// Wochenstreifen
// ---------------------------------------------------------------------------

/**
 * Wochentagskuerzel, wie sie in [TrainingSession.day] stehen — ein interner
 * Code, keine Anzeige (angezeigt ueber `texts.format.weekdayShort/Long`).
 * `:core` haelt dieselbe Tabelle privat (`PLAN_WEEKDAY_CODES`); eine
 * Aenderung dort muss hier nachgezogen werden.
 */
internal val PLAN_DAY_CODES = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

/** 0 = Montag … 6 = Sonntag; `-1` bei fremdem Kuerzel. */
internal fun planDayIndex(day: String): Int = PLAN_DAY_CODES.indexOf(day)

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

/**
 * Ein Tag im Streifen samt fertigem Vorlesetext.
 *
 * [description] ist der ganze Satz, den TalkBack statt Kuerzel und Zahl
 * vorliest („Dienstag: 32 km gefahren", „Mittwoch: Ruhetag") — sonst hoerte
 * man nur „Di", „32" und muesste die Bedeutung des Kreises erraten.
 *
 * [label] ist das Wochentagskuerzel in der Sprache von `texts` („Di" / „Tue").
 */
data class StripDay(
    val label: String,
    val state: StripState,
    val km: Int?,
    val isToday: Boolean,
    val description: UiText,
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
 * @param texts liefert die Wochentagsnamen in der Sprache der Oberflaeche.
 */
fun weekStrip(
    today: LocalDate,
    sessions: List<TrainingSession>,
    riddenKm: Map<LocalDate, Double>,
    todayKm: Int?,
    texts: CoreTexts,
): List<StripDay> {
    val monday = today.with(DayOfWeek.MONDAY)
    return (0..6).map { index ->
        val date = monday.plusDays(index.toLong())
        val isToday = date == today
        val ridden = riddenKm[date]?.takeIf { it > 0 }
        val plannedKm = sessions.filter { planDayIndex(it.day) == index }.maxOfOrNull { it.targetKm }
        val code = PLAN_DAY_CODES[index]
        val label = texts.format.weekdayShort(code)
        val weekday = texts.format.weekdayLong(code)
        val name: Any = if (isToday) UiText.Res(R.string.today_strip_today_name, listOf(weekday)) else weekday
        when {
            ridden != null -> StripDay(
                label,
                StripState.DONE,
                ridden.roundToInt(),
                isToday,
                UiText.Res(R.string.today_strip_ridden_cd, listOf(name, ridden.roundToInt())),
            )

            isToday -> StripDay(
                label,
                StripState.TODAY,
                todayKm,
                true,
                if (todayKm != null) {
                    UiText.Res(R.string.today_strip_planned_cd, listOf(name, todayKm))
                } else {
                    UiText.Res(R.string.today_strip_rest_day_cd, listOf(name))
                },
            )

            date.isAfter(today) && plannedKm != null -> StripDay(
                label,
                StripState.PLANNED,
                plannedKm,
                false,
                UiText.Res(R.string.today_strip_planned_cd, listOf(name, plannedKm)),
            )

            // Vorgelesen wird zwischen Vergangenheit und Zukunft unterschieden,
            // obwohl beide gleich aussehen („–"): Ein vergangener Tag ohne Fahrt
            // war nicht zwingend ein Ruhetag (auch ein verpasster Plantag landet
            // hier), ein kommender ohne Einheit ist es.
            else -> StripDay(
                label,
                StripState.REST,
                null,
                false,
                UiText.Res(
                    if (date.isBefore(today)) R.string.today_strip_no_ride_cd else R.string.today_strip_rest_day_cd,
                    listOf(name),
                ),
            )
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
): Pair<UiText, UiText?> {
    val km = riddenKm.roundToInt()
    if (targetKm == null) {
        val rides = if (rideCount <= 0) {
            UiText.Res(R.string.today_week_rides_none)
        } else {
            UiText.Plural(R.plurals.today_week_rides_count, rideCount)
        }
        return UiText.Res(R.string.today_week_km_label, listOf(km)) to rides
    }
    val open = strip.count {
        it.state == StripState.PLANNED || (it.state == StripState.TODAY && it.km != null)
    }
    // Erreicht schlaegt offen: „79 von 40 km · noch 1 Fahrt" las sich wie ein
    // Rueckstand, obwohl das Ziel doppelt erfuellt war. Verglichen wird die
    // gerundete Zahl — dieselbe, die davor steht; „40 von 40 km" ohne
    // „geschafft" waere derselbe Widerspruch in klein.
    val extra = when {
        km >= targetKm -> UiText.Res(R.string.today_week_goal_reached_status)
        open > 0 -> UiText.Plural(R.plurals.today_week_open_rides_count, open)
        else -> null
    }
    return UiText.Res(R.string.today_week_progress_label, listOf(km, targetKm)) to extra
}

// ---------------------------------------------------------------------------
// Ziel
// ---------------------------------------------------------------------------

/** „noch 12 Wochen" / „noch 5 Tage" / „morgen" / „heute" / „vorbei". */
fun goalCountdown(today: LocalDate, goalDate: LocalDate): UiText {
    val days = ChronoUnit.DAYS.between(today, goalDate)
    return when {
        days < 0 -> UiText.Res(R.string.today_goal_countdown_past)
        days == 0L -> UiText.Res(R.string.today_goal_countdown_today)
        days == 1L -> UiText.Res(R.string.today_goal_countdown_tomorrow)
        days < 14 -> UiText.Plural(R.plurals.today_goal_countdown_days_count, days.toInt())
        else -> UiText.Plural(R.plurals.today_goal_countdown_weeks_count, (days / 7).toInt())
    }
}

/**
 * „Sa, 20. Dezember" / „Sat 20 December" — mit Jahr, wenn es nicht das
 * laufende ist.
 *
 * Kein `DateTimeFormatter` mit Muster „EE": Der schreibt im Deutschen „Sa.",
 * die Seite zeigt seit jeher das Plan-Kuerzel „Sa" ohne Punkt. Kuerzel und
 * Monat kommen deshalb einzeln ([texts], [formatMonthName]), die Stellung
 * aus der Ressource.
 */
fun formatGoalDate(today: LocalDate, goalDate: LocalDate, texts: CoreTexts): UiText {
    val weekday = texts.format.weekdayShort(PLAN_DAY_CODES[goalDate.dayOfWeek.value - 1])
    val month = formatMonthName(goalDate, texts.format.language)
    return if (goalDate.year != today.year) {
        UiText.Res(R.string.today_goal_date_year, listOf(weekday, goalDate.dayOfMonth, month, goalDate.year))
    } else {
        UiText.Res(R.string.today_goal_date, listOf(weekday, goalDate.dayOfMonth, month))
    }
}

/**
 * Die gedaempfte Zeile unter dem Zielnamen: „Sa, 20. Dezember · noch 12
 * Wochen · Woche 3 von 15". Die Planwoche entfaellt vor Planbeginn
 * ([weekIndex] < 0).
 *
 * Liefert die Teile; die Anzeige verbindet sie nach dem Aufloesen mit dem
 * sprachneutralen „ · ".
 */
fun goalLine(
    today: LocalDate,
    goalDate: LocalDate,
    weekIndex: Int,
    weekCount: Int,
    texts: CoreTexts,
): List<UiText> = buildList {
    add(formatGoalDate(today, goalDate, texts))
    add(goalCountdown(today, goalDate))
    if (weekIndex >= 0 && weekCount > 0) {
        add(UiText.Res(R.string.today_goal_plan_week_label, listOf(weekIndex + 1, weekCount)))
    }
}

/**
 * Die Ziel-Zeile, wenn eine Zielzeit eingetragen ist (Fuehrung „Klartext"):
 * „Ziel 2:10 h · Stand heute ca. 2:25 h · noch 12 Wochen". Ohne Prognose
 * (zu wenige passende Touren) entfaellt der mittlere Teil.
 */
fun goalTimeLine(today: LocalDate, goalDate: LocalDate, targetMin: Int, currentMin: Int?): List<UiText> =
    buildList {
        add(UiText.Res(R.string.today_goal_target_time_label, listOf(formatGoalDuration(targetMin))))
        currentMin?.let {
            add(UiText.Res(R.string.today_goal_current_time_label, listOf(formatGoalDuration(it))))
        }
        add(goalCountdown(today, goalDate))
    }

/** Anteil der Zeit von Planbeginn bis Zieltag, der schon hinter uns liegt (0…1). */
fun goalProgress(planStart: LocalDate, goalDate: LocalDate, today: LocalDate): Float {
    val total = ChronoUnit.DAYS.between(planStart, goalDate)
    if (total <= 0) return 1f
    val done = ChronoUnit.DAYS.between(planStart, today)
    return (done.toFloat() / total).coerceIn(0f, 1f)
}
