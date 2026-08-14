package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.math.max
import kotlin.math.min

/**
 * Trainingsplan-Generator und -Persistenz.
 *
 * 1:1-Portierung von `lib/training.dart` und damit semantisch identisch zur
 * Web-App-Referenz (training.ts): gleiches Wochenraster (Montag–Sonntag
 * lokaler Zeit), gleiche Progression und gleiches JSON-Format, damit Plaene
 * zwischen Web-App und dieser App austauschbar bleiben.
 *
 * Der einzige IO-Rand des Dart-Originals ist `SharedPreferences`. Er wird hier
 * ueber [TrainingPlanStore] hereingereicht; die Android-Implementierung folgt
 * in Phase 3.
 */

/** Speicherschluessel des Plans (Dart: `_storageKey`). */
const val trainingPlanStorageKey: String = "trailscape.plan"

private val weekdays = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

private const val MIN_WEEKS = 3
private const val MAX_WEEKS = 52

const val errorTooSoon: String =
    "Das Ziel liegt zu nah in der Zukunft – plane mindestens 3 Wochen ein."
const val errorTooFar: String = "Das Ziel liegt mehr als ein Jahr entfernt."

/** Basisvolumen pro Woche in km, falls die bisherige Belastung darunter liegt. */
private val levelBaseKm: Map<FitnessLevel, Double> = mapOf(
    FitnessLevel.EINSTEIGER to 40.0,
    FitnessLevel.FORTGESCHRITTEN to 70.0,
    FitnessLevel.AMBITIONIERT to 110.0,
)

private const val RECOVERY_FACTOR = 0.6
private const val TAPER_FACTOR = 0.5
private const val PEAK_DISTANCE_FACTOR = 1.3
private const val ACTIVATION_KM = 15
private const val CLIMBING_HINT =
    " Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des Ziels zu gewöhnen."
private const val CLIMB_HINT_THRESHOLD_M = 1000.0

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

/**
 * Groesster zugelassener Sprung des Wochenvolumens von einer Aufbauwoche zur
 * naechsten (Anteil, 0,15 = +15 %).
 *
 * ## Warum es die Grenze ueberhaupt braucht
 * Ohne sie ergibt sich die Wochenvorgabe rein aus der linearen Interpolation
 * `startKm → peakKm` ueber die Zahl der Aufbauwochen. Bei wenigen Aufbauwochen
 * war der „Fortschritt" in Woche 1 bereits 1,0 — ein Ambitionierter mit 150
 * km/Woche und einem 200-km-Ziel in drei Wochen bekam sofort 260 km
 * vorgeschrieben, **+73 %**. Die App selbst meldet so etwas anschliessend als
 * Belastungssprung (`assessDeload`: akute Last deutlich ueber dem gewohnten
 * Niveau, Wochenlast > 1,3 × Vierwochenmittel) und ab einer Rampenrate von
 * 8 CTL/Woche als „zu schnell gestiegen". Ein Plan, der vorschreibt, was die
 * eigene Auswertung danach anmahnt, ist in sich widerspruechlich.
 *
 * ## Warum ausgerechnet 15 %
 * Die verbreitete Faustregel ist die „10-%-Regel"; sie ist das konservative
 * Ende und fuer Einsteiger gedacht. Die App zieht ihre eigene Warnschwelle bei
 * **+30 %** Wochenlast gegen das Vierwochenmittel. 15 % liegt sauber in der
 * Mitte: klar unter der Schwelle, ab der die Auswertung widerspricht, und
 * grosszuegig genug, dass ein Plan in acht Aufbauwochen das Volumen noch
 * verdreifachen kann (1,15⁸ ≈ 3,1). Die Grenze wirkt gegen die **letzte
 * Aufbauwoche**, nicht gegen die Vorwoche — nach einer Erholungswoche bei 60 %
 * ist die Rueckkehr auf das bereits vertragene Niveau kein neuer Reiz, und
 * gegen die Erholungswoche gerechnet waere jeder Wiederaufbau verboten.
 */
private const val MAX_WEEKLY_INCREASE = 0.15

/**
 * Mindestanteil der Zieldistanz, den die **laengste geplante Fahrt** erreichen
 * muss, damit der Plan das Ziel traegt (0,6 = 60 %).
 *
 * ## Warum es die Pruefung braucht
 * Vorher gab es sie gar nicht. Ein Einsteiger ohne Historie mit dem Ziel
 * „200 km in 12 Wochen" bekam einen Plan, dessen laengste Trainingsfahrt 45 km
 * war — und in Woche 12 stand ein 200-km-Event. Die App meldete „Plan mit 12
 * Wochen erstellt." und schwieg zum Rest.
 *
 * ## Warum 60 %
 * Fuer lange Ausdauerveranstaltungen ist es gaengige Praxis, die volle
 * Zieldistanz **nicht** im Training zu fahren; die laengste Vorbereitungsfahrt
 * liegt ueblicherweise bei rund zwei Dritteln bis drei Vierteln der
 * Zieldistanz, den Rest tragen Wochenvolumen, Taper und Renntag. 60 % ist die
 * untere Kante dieses Korridors: darunter ist der Zieltag kein „laenger als
 * gewohnt" mehr, sondern Neuland. Die Anteile der Schluesseleinheit am
 * Wochenvolumen (0,45…0,6, siehe [aufbauSessions]) sind so gewaehlt, dass ein
 * Plan, der seinen Peak von [PEAK_DISTANCE_FACTOR] × Zieldistanz **erreicht**,
 * diese Schwelle sicher nimmt — die Warnung erscheint also genau dann, wenn der
 * Aufbau die Zieldistanz tatsaechlich nicht einholt.
 */
const val minLongestRideShare: Double = 0.6

/**
 * Nominales Planungstempo in km/h — die Bruecke zwischen Beschreibungstext und
 * Kilometern einer Einheit.
 *
 * `generatePlan` kennt weder Historie noch Profil (es bekommt nur eine
 * [FitnessAssessment]), deshalb dieselbe Annahme wie im Rest der App:
 * [fallbackGravelSpeedKmh] fuer gemischtes Gravel-Terrain, moduliert mit
 * [intensitySpeedFactor]. Die tatsaechliche Routenlaenge rechnet spaeter
 * [routeTargetForSession] mit dem echten Historien-Median; hier geht es nur
 * darum, dass „4×8 Minuten" und „14 km" nicht mehr auseinanderlaufen.
 */
private fun nominalSpeedKmh(intensity: SessionIntensity): Double =
    fallbackGravelSpeedKmh * intensitySpeedFactor(intensity)

/** Fahrzeit in Minuten, die [km] bei nominalem Tempo brauchen. */
private fun minutesFor(km: Double, intensity: SessionIntensity): Int =
    max(1L, dartRound(km / nominalSpeedKmh(intensity) * 60).toLong()).toInt()

/** Umgekehrt: die Kilometer, die [minutes] bei nominalem Tempo ergeben. */
private fun kmForMinutes(minutes: Int, intensity: SessionIntensity): Double =
    minutes / 60.0 * nominalSpeedKmh(intensity)

/**
 * Persistenz des Trainingsplans — in Dart `SharedPreferences` unter
 * [trainingPlanStorageKey].
 *
 * Bewusst nur ein String-Schluessel: das JSON-Format bleibt damit in [savePlan]
 * bzw. [loadPlan] und ist ohne Plattform testbar.
 */
interface TrainingPlanStore {
    /** Gespeichertes JSON, `null` wenn nichts gespeichert ist. */
    fun read(): String?

    fun write(value: String)

    fun remove()
}

/** Plan nur im Arbeitsspeicher — Vorgabe und Attrappe in Tests. */
class InMemoryTrainingPlanStore(private var value: String? = null) : TrainingPlanStore {
    override fun read(): String? = value

    override fun write(value: String) {
        this.value = value
    }

    override fun remove() {
        value = null
    }
}

/** Montag 00:00 lokaler Zeit der Woche, in der [timestamp] liegt. */
private fun startOfWeek(timestamp: Long): Long {
    val date = dartLocalOf(timestamp)
    // DateTime.weekday: 1 = Montag … 7 = Sonntag
    val offset = date.dayOfWeek.value - 1
    // Kalenderarithmetik statt Duration – dadurch DST-sicher auf 00:00 lokal.
    return dartEpochMs(date.toLocalDate().minusDays(offset.toLong()).atStartOfDay())
}

/** Addiert [weeks] Wochen und bleibt dabei DST-sicher auf 00:00 lokaler Zeit. */
private fun addWeeks(timestamp: Long, weeks: Int): Long {
    val date = dartLocalOf(timestamp)
    return dartEpochMs(date.toLocalDate().plusDays(weeks * 7L).atStartOfDay())
}

/** Index des Wochentags (0 = Mo … 6 = So). */
private fun weekdayIndex(timestamp: Long): Int = dartLocalOf(timestamp).dayOfWeek.value - 1

private fun round5(km: Double): Int = max(5, dartRound(km / 5).toInt() * 5)

/**
 * Wie [round5], aber nie ueber [capKm] hinaus.
 *
 * Das Runden auf 5 km kann eine Obergrenze um bis zu 2,5 km ueberschiessen —
 * bei einer Rampengrenze ([MAX_WEEKLY_INCREASE]) waere das genau die Sorte
 * stiller Ueberschreitung, gegen die die Grenze gedacht ist. Deshalb notfalls
 * eine Stufe zurueck; unter 5 km geht es nie.
 */
private fun round5AtMost(km: Double, capKm: Double): Int {
    val rounded = round5(min(km, capKm))
    return if (rounded > capKm && rounded > 5) rounded - 5 else rounded
}

/**
 * Verteilt [totalKm] nach [shares] auf ganze Kilometer (mindestens 1 je
 * Einheit). Die Anteile muessen nicht auf 1 summieren — sie werden auf ihre
 * Summe normiert, damit sich eine Einheit mit eigener Laengenlogik (siehe
 * [intervalSession]) sauber herausrechnen laesst.
 */
private fun splitKm(totalKm: Int, shares: List<Double>): List<Int> {
    val sum = shares.sum()
    if (sum <= 0 || totalKm <= 0) {
        return shares.map { 1 }
    }
    return shares.map { max(1, dartRound(totalKm * it / sum).toInt()) }
}

/**
 * Baut eine Einheit, deren Kilometer sich aus [km] und deren Dauer sich aus
 * dem nominalen Tempo der Intensitaet ergibt.
 */
private fun session(
    day: String,
    title: String,
    description: String,
    km: Int,
    intensity: SessionIntensity,
): TrainingSession = TrainingSession(
    day = day,
    title = title,
    description = description,
    targetKm = km,
    intensity = intensity,
    durationMin = minutesFor(km.toDouble(), intensity),
)

// ---------------------------------------------------------------------------
// Intervalleinheiten: Inhalt und Kilometer haengen zusammen
// ---------------------------------------------------------------------------

/**
 * Der Aufbau einer Intervalleinheit — die Groesse, aus der **sowohl** der Text
 * **als auch** die Kilometer entstehen.
 *
 * ## Warum das nicht mehr ueber einen Wochenanteil laeuft
 * Bisher bekam „Intervalle" 0,2 × Wochenvolumen. Bei einer 70-km-Woche waren
 * das 14 km — waehrend die Beschreibung 20 Minuten Einfahren, 4×8 Minuten
 * Belastung und 3×4 Minuten Pause verlangte, zusammen rund 70 Minuten bzw.
 * 30 km. Die daraus generierte Runde war dann halb so lang wie die Einheit,
 * die darauf stattfinden sollte.
 *
 * Jetzt ist die Reihenfolge umgedreht: Aus dem Wochenanteil wird eine
 * **Wunschdauer**, daraus die Zahl der Wiederholungen (geklemmt auf einen
 * sinnvollen Bereich), daraus die tatsaechliche Dauer und daraus erst die
 * Kilometer. Text und Zahl koennen damit nicht mehr auseinanderlaufen, und der
 * Rest des Wochenvolumens geht an die Einheiten, die Volumen tragen sollen.
 */
private data class IntervalShape(
    val warmupMin: Int,
    val workMin: Int,
    val restMin: Int,
    val cooldownMin: Int,
    val minReps: Int,
    val maxReps: Int,
    /** Wie die Belastung im Text benannt wird. */
    val effort: String,
) {
    fun minutesFor(reps: Int): Int =
        warmupMin + reps * workMin + (reps - 1) * restMin + cooldownMin
}

/** 4×8-Raster fuer Fortgeschrittene (3…5 Wiederholungen je nach Wochenvolumen). */
private val intervalShapeFortgeschritten = IntervalShape(
    warmupMin = 20,
    workMin = 8,
    restMin = 4,
    cooldownMin = 10,
    minReps = 3,
    maxReps = 5,
    effort = "zügig im Schwellenbereich",
)

/** 5×6-Raster fuer Ambitionierte (4…6 Wiederholungen). */
private val intervalShapeAmbitioniert = IntervalShape(
    warmupMin = 20,
    workMin = 6,
    restMin = 3,
    cooldownMin = 10,
    minReps = 4,
    maxReps = 6,
    effort = "hart an der Schwelle",
)

/**
 * Die Intervalleinheit zu einem gewuenschten Umfang [wantedKm]: Struktur
 * zuerst, Kilometer danach (Begruendung in [IntervalShape]).
 */
private fun intervalSession(day: String, shape: IntervalShape, wantedKm: Double): TrainingSession {
    val wantedMin = minutesFor(max(wantedKm, 1.0), SessionIntensity.HART)
    val room = wantedMin - shape.warmupMin - shape.cooldownMin
    val fitting = if (room <= 0) 0 else room / (shape.workMin + shape.restMin)
    val reps = fitting.coerceIn(shape.minReps, shape.maxReps)
    val minutes = shape.minutesFor(reps)
    val km = max(1, dartRound(kmForMinutes(minutes, SessionIntensity.HART)).toInt())

    return TrainingSession(
        day = day,
        title = "Intervalle",
        description = "Nach ${shape.warmupMin} Minuten Einfahren $reps×${shape.workMin} Minuten " +
            "${shape.effort}, dazwischen je ${shape.restMin} Minuten locker rollen; zum " +
            "Abschluss ${shape.cooldownMin} Minuten ausfahren – zusammen rund $minutes Minuten.",
        targetKm = km,
        intensity = SessionIntensity.HART,
        durationMin = minutes,
    )
}

private fun longTourDescription(goal: Goal): String {
    val base = "Die Schlüsseleinheit der Woche: gleichmäßig im Grundlagentempo fahren und " +
        "konsequent essen und trinken."
    val ascentM = goal.ascentM
    if (ascentM != null && ascentM >= CLIMB_HINT_THRESHOLD_M) {
        return base + CLIMBING_HINT
    }
    return base
}

private fun buildSessions(
    kind: WeekKind,
    level: FitnessLevel,
    targetKm: Int,
    goal: Goal,
): List<TrainingSession> {
    if (kind == WeekKind.ZIELWOCHE) {
        return zielwocheSessions(goal)
    }

    if (kind == WeekKind.ERHOLUNG) {
        val km = splitKm(targetKm, listOf(0.5, 0.5))
        return listOf(
            session(
                day = "Di",
                title = "Lockere Ausfahrt",
                description = "Entspannt rollen, kleine Gänge und hohe Trittfrequenz – diese " +
                    "Woche dient ausschließlich der Erholung.",
                km = km[0],
                intensity = SessionIntensity.LOCKER,
            ),
            session(
                day = "Sa",
                title = "Ruhige Runde",
                description = "Gemütliche Ausfahrt ohne Leistungsdruck, halte den Puls " +
                    "durchgehend im niedrigen Bereich.",
                km = km[1],
                intensity = SessionIntensity.LOCKER,
            ),
        )
    }

    if (kind == WeekKind.TAPER) {
        val km = splitKm(targetKm, listOf(0.55, 0.45))
        return listOf(
            session(
                day = "Di",
                title = "Locker mit Antritten",
                description = "Locker rollen und dabei 3 kurze Antritte über je 30 Sekunden " +
                    "einstreuen, um spritzig zu bleiben.",
                km = km[0],
                intensity = SessionIntensity.LOCKER,
            ),
            session(
                day = "Do",
                title = "Kurze lockere Ausfahrt",
                description = "Kurz und ruhig fahren, danach Material checken und die Beine " +
                    "bewusst schonen.",
                km = km[1],
                intensity = SessionIntensity.LOCKER,
            ),
        )
    }

    return aufbauSessions(level, targetKm, goal)
}

/**
 * Die Einheiten einer Aufbauwoche.
 *
 * ## Wie die Kilometer verteilt werden
 * Zuerst bekommt die **Intervalleinheit** ihre Laenge aus ihrem eigenen Inhalt
 * (siehe [intervalSession]) — sie ist die einzige Einheit, deren Umfang nicht
 * verhandelbar ist, weil ihr Text eine feste Struktur beschreibt. Was danach
 * vom Wochenvolumen uebrig ist, geht nach Anteilen an die Einheiten, die
 * Volumen tragen sollen.
 *
 * Die Anteile der **Schluesseleinheit** („Lange Tour": 0,6 bzw. 0,5 beim
 * Einsteiger, 0,55 fortgeschritten, 0,45 ambitioniert von der Restmenge) sind
 * nicht frei gewaehlt: Sie sind der Grund, warum ein Plan, der seinen Peak von
 * [PEAK_DISTANCE_FACTOR] × Zieldistanz erreicht, die
 * [minLongestRideShare]-Schwelle auch nimmt. Wer sie senkt, macht die
 * Machbarkeitswarnung zum Fehlalarm — beides gehoert zusammen.
 */
private fun aufbauSessions(
    level: FitnessLevel,
    targetKm: Int,
    goal: Goal,
): List<TrainingSession> {
    if (level == FitnessLevel.EINSTEIGER) {
        // Einsteiger fahren keine Schwellenintervalle: erst Umfang, dann Härte.
        val withRecovery = targetKm >= 60
        val shares = if (withRecovery) listOf(0.3, 0.5, 0.2) else listOf(0.4, 0.6)
        val km = splitKm(targetKm, shares)
        val sessions = mutableListOf(
            session(
                day = "Di",
                title = "Lockere Ausfahrt GA1",
                description = "Ruhiges Grundlagentempo – du solltest dich während der gesamten " +
                    "Fahrt unterhalten können.",
                km = km[0],
                intensity = SessionIntensity.GRUNDLAGE,
            ),
            session(
                day = "Sa",
                title = "Lange Tour",
                description = longTourDescription(goal),
                km = km[1],
                intensity = SessionIntensity.GRUNDLAGE,
            ),
        )
        if (withRecovery) {
            sessions.add(
                session(
                    day = "So",
                    title = "Regeneration locker",
                    description = "Kurze Regenerationsrunde im leichten Gang, bewusst niedrige " +
                        "Intensität für frische Beine.",
                    km = km[2],
                    intensity = SessionIntensity.LOCKER,
                ),
            )
        }
        return sessions
    }

    if (level == FitnessLevel.FORTGESCHRITTEN) {
        val intervals = intervalSession("Do", intervalShapeFortgeschritten, targetKm * 0.2)
        val rest = max(0, targetKm - intervals.targetKm)
        val km = splitKm(rest, listOf(0.25, 0.55))
        return listOf(
            session(
                day = "Di",
                title = "GA1",
                description = "Lockere Grundlageneinheit zum Auffüllen des Wochenvolumens, Puls " +
                    "konstant im GA1-Bereich halten.",
                km = km[0],
                intensity = SessionIntensity.GRUNDLAGE,
            ),
            intervals,
            session(
                day = "Sa",
                title = "Lange Tour",
                description = longTourDescription(goal),
                km = km[1],
                intensity = SessionIntensity.GRUNDLAGE,
            ),
        )
    }

    val intervals = intervalSession("Mi", intervalShapeAmbitioniert, targetKm * 0.2)
    val rest = max(0, targetKm - intervals.targetKm)
    val km = splitKm(rest, listOf(0.2, 0.45, 0.15))
    return listOf(
        session(
            day = "Di",
            title = "GA1",
            description = "Ruhige Grundlageneinheit, gleichmäßige Belastung ohne Spitzen und " +
                "ohne Sprints.",
            km = km[0],
            intensity = SessionIntensity.GRUNDLAGE,
        ),
        intervals,
        session(
            day = "Sa",
            title = "Lange Tour",
            description = longTourDescription(goal),
            km = km[1],
            intensity = SessionIntensity.GRUNDLAGE,
        ),
        session(
            day = "So",
            title = "GA1 kompensatorisch",
            description = "Kompensationsrunde mit hoher Trittfrequenz, um die Beine nach der " +
                "langen Tour wieder locker zu fahren.",
            km = km[2],
            intensity = SessionIntensity.LOCKER,
        ),
    )
}

private fun zielwocheSessions(goal: Goal): List<TrainingSession> {
    val eventIndex = weekdayIndex(goal.date)
    val eventDay = weekdays[eventIndex]
    val eventKm = max(1, dartRound(goal.distanceKm).toInt())
    val ascentM = goal.ascentM

    val eventSession = TrainingSession(
        day = eventDay,
        title = "Zielevent: ${goal.name}",
        description = if (ascentM != null && ascentM >= CLIMB_HINT_THRESHOLD_M) {
            "Dein Zielevent über $eventKm km und rund ${dartRound(ascentM).toInt()} Hm – " +
                "teile dir die Kraft an den Anstiegen ein und trinke von Beginn an regelmäßig."
        } else {
            "Dein Zielevent über $eventKm km – starte kontrolliert, halte dein Tempo und " +
                "versorge dich unterwegs konsequent."
        },
        targetKm = eventKm,
        intensity = SessionIntensity.HART,
        // Keine Dauerangabe: Wie lange das Event dauert, entscheidet der
        // Renntag, nicht unser Planungstempo.
        durationMin = null,
        // Das Event hat eine eigene Strecke — dafuer wird nie eine Runde
        // generiert (siehe [canGenerateRouteFor]).
        isEvent = true,
    )

    val activationDay = if (eventIndex > 1) "Di" else if (eventIndex == 1) "Mo" else null
    if (activationDay == null) {
        return listOf(eventSession)
    }

    return listOf(
        session(
            day = activationDay,
            title = "Aktivierung locker",
            description = "Kurze lockere Runde mit ein paar Antritten, danach Rad und " +
                "Verpflegung für den Zieltag vorbereiten.",
            km = ACTIVATION_KM,
            intensity = SessionIntensity.LOCKER,
        ),
        eventSession,
    )
}

private fun planWeekKinds(weekCount: Int): List<WeekKind> {
    val kinds = mutableListOf<WeekKind>()
    val lastBuildIndex = weekCount - 3

    for (i in 0 until weekCount) {
        when {
            i == weekCount - 1 -> kinds.add(WeekKind.ZIELWOCHE)
            i == weekCount - 2 -> kinds.add(WeekKind.TAPER)
            // Jede 4. Woche ist Erholung – ausser sie waere die letzte
            // Aufbauwoche vor dem Taper.
            i % 4 == 3 && i != lastBuildIndex -> kinds.add(WeekKind.ERHOLUNG)
            else -> kinds.add(WeekKind.AUFBAU)
        }
    }

    return kinds
}

/** Startvolumen eines Plans: die bisherige Wochenleistung, mindestens das Basisvolumen der Stufe. */
private fun startKmFor(assessment: FitnessAssessment): Double =
    max(assessment.weeklyKm, levelBaseKm.getValue(assessment.level))

/** Angestrebtes Spitzenvolumen — die Absicht, nicht die Zusage (siehe [planWeekVolumes]). */
private fun peakKmFor(goal: Goal, startKm: Double): Double =
    max(goal.distanceKm * PEAK_DISTANCE_FACTOR, startKm)

/**
 * Die Wochenvolumina eines Plans — die Stelle, an der aus einer Absicht ein
 * tragfaehiger Aufbau wird.
 *
 * Frueher stand hier die reine Interpolation `startKm → peakKm` ueber die Zahl
 * der Aufbauwochen, gedeckelt durch `startKm × 2,2`. Dieser Deckel hat die
 * Logik lautlos abgeschaltet: Er begrenzte den **Peak**, nicht den **Weg**
 * dorthin. Bei einer einzigen Aufbauwoche war der Fortschritt sofort 1,0, und
 * die erste Woche sprang auf das Spitzenvolumen.
 *
 * Jetzt gilt beides:
 *
 *  * Der **Peak bleibt die Absicht** ([PEAK_DISTANCE_FACTOR] × Zieldistanz) —
 *    er wird nicht mehr kuenstlich gekappt, sondern schlicht nur erreicht, wenn
 *    die Zeit dafuer reicht.
 *  * Die **Rampe** begrenzt jede Aufbauwoche auf [MAX_WEEKLY_INCREASE] gegen
 *    die zuletzt erreichte Aufbauwoche. Damit gibt es keinen Sprung auf den
 *    Peak mehr, auch nicht bei sehr kurzen Zeitraeumen.
 *  * Der **Taper** rechnet mit dem *tatsaechlich erreichten* Peak, nicht mit
 *    dem angestrebten. Sonst haette die vorletzte Woche 50 % eines Volumens
 *    vorgeschrieben, das nie gefahren wurde.
 *
 * @param eventWeekKm Wochenvolumen der Zielwoche (Aktivierung + Event).
 */
private fun planWeekVolumes(
    kinds: List<WeekKind>,
    startKm: Double,
    peakKm: Double,
    eventWeekKm: Int,
): List<Int> {
    val buildCount = kinds.count { it == WeekKind.AUFBAU }
    val targets = ArrayList<Int>(kinds.size)

    var buildSeen = 0
    var previousKm = startKm
    var lastBuildKm = startKm
    var achievedPeakKm = startKm

    for (kind in kinds) {
        val targetKm = when (kind) {
            WeekKind.AUFBAU -> {
                val progress = if (buildCount > 1) buildSeen.toDouble() / (buildCount - 1) else 1.0
                val wanted = startKm + (peakKm - startKm) * progress
                val km = round5AtMost(wanted, lastBuildKm * (1 + MAX_WEEKLY_INCREASE))
                buildSeen += 1
                lastBuildKm = km.toDouble()
                achievedPeakKm = max(achievedPeakKm, km.toDouble())
                km
            }

            WeekKind.ERHOLUNG -> round5(previousKm * RECOVERY_FACTOR)
            WeekKind.TAPER -> round5(achievedPeakKm * TAPER_FACTOR)
            WeekKind.ZIELWOCHE -> eventWeekKm
        }
        previousKm = targetKm.toDouble()
        targets.add(targetKm)
    }

    return targets
}

/**
 * Erzeugt einen Trainingsplan vom Montag der aktuellen Woche bis zur
 * Zielwoche.
 *
 * Wirft [IllegalArgumentException] (Darts `ArgumentError`), wenn das Ziel
 * weniger als 3 oder mehr als 52 Wochen entfernt liegt.
 *
 * **Der Plan sagt nicht, ob er traegt.** Ob die laengste geplante Fahrt die
 * Zieldistanz ueberhaupt einholt, beantwortet [assessPlanFeasibility] — und die
 * Antwort gehoert vor die Augen der Nutzerin, bevor sie zwoelf Wochen darauf
 * baut.
 */
fun generatePlan(
    goal: Goal,
    assessment: FitnessAssessment,
    now: Long? = null,
): TrainingPlan {
    val nowMs = now ?: System.currentTimeMillis()
    val firstMonday = startOfWeek(nowMs)
    val goalMonday = startOfWeek(goal.date)
    val weekCount = dartRound((goalMonday - firstMonday).toDouble() / WEEK_MS).toInt() + 1

    if (weekCount < MIN_WEEKS) {
        throw IllegalArgumentException(errorTooSoon)
    }
    if (weekCount > MAX_WEEKS) {
        throw IllegalArgumentException(errorTooFar)
    }

    val level = assessment.level
    val startKm = startKmFor(assessment)
    val kinds = planWeekKinds(weekCount)
    val volumes = planWeekVolumes(
        kinds = kinds,
        startKm = startKm,
        peakKm = peakKmFor(goal, startKm),
        eventWeekKm = zielwocheSessions(goal).sumOf { it.targetKm },
    )

    val weeks = kinds.indices.map { i ->
        TrainingWeek(
            index = i,
            start = addWeeks(firstMonday, i),
            end = addWeeks(firstMonday, i + 1),
            kind = kinds[i],
            targetKm = volumes[i],
            sessions = buildSessions(kinds[i], level, volumes[i], goal),
        )
    }

    return TrainingPlan(
        createdAt = nowMs,
        goal = goal,
        level = level,
        weeks = weeks,
    )
}

// ---------------------------------------------------------------------------
// Plausibilitaet: traegt der Plan das Ziel?
// ---------------------------------------------------------------------------

/**
 * Das Urteil ueber einen Plan: Holt die laengste geplante Fahrt die Zieldistanz
 * ein — und wenn nicht, was waere stattdessen realistisch?
 *
 * Bewusst **kein** Feld von [TrainingPlan]: Das JSON-Format des Plans ist mit
 * Web-App und Sync-Server abgestimmt, und ein Urteil ist ohnehin kein
 * Speicherinhalt, sondern eine Rechnung ueber den gespeicherten Plan.
 */
data class PlanFeasibility(
    /** Laengste geplante Trainingsfahrt in km (ohne das Zielevent selbst). */
    val longestRideKm: Int,
    val goalDistanceKm: Double,
    /** [longestRideKm] / [goalDistanceKm]; 0, wenn die Zieldistanz unsinnig ist. */
    val coverage: Double,
    /** `true`, wenn [coverage] mindestens [minLongestRideShare] erreicht. */
    val feasible: Boolean,
    /**
     * Zieldistanz, die dieser Plan in seiner Laufzeit tatsaechlich truege —
     * `null`, wenn der Plan ohnehin traegt.
     */
    val suggestedDistanceKm: Int?,
    /**
     * Wochen, die es fuer die **gewuenschte** Zieldistanz braeuchte — `null`,
     * wenn der Plan traegt oder selbst ein Jahr Vorbereitung nicht reicht.
     */
    val suggestedWeeks: Int?,
    /** Fertiger deutscher Hinweistext; `null`, wenn es nichts zu sagen gibt. */
    val message: String?,
)

/**
 * Prueft, ob [plan] sein eigenes Ziel traegt, und macht sonst einen konkreten
 * Gegenvorschlag.
 *
 * ## Was geprueft wird
 * Die **laengste geplante Trainingsfahrt** gegen die Zieldistanz. Das Zielevent
 * selbst zaehlt nicht mit — es ist die Pruefung, nicht die Vorbereitung. Liegt
 * das Verhaeltnis unter [minLongestRideShare], ist der Zieltag kein „laenger
 * als gewohnt", sondern Neuland, und die App sagt das.
 *
 * ## Woher der Gegenvorschlag kommt
 *  * **Machbare Distanz**: die Distanz, zu der die laengste geplante Fahrt
 *    genau [minLongestRideShare] waere — also das Ziel, das dieser Plan
 *    truege, auf 5 km gerundet.
 *  * **Noetige Wochen**: es wird derselbe Aufbau fuer wachsende Laufzeiten
 *    durchgerechnet (bis [MAX_WEEKS]) und die erste Laufzeit gemeldet, deren
 *    laengste Fahrt die Schwelle nimmt. Kein zweites Modell, sondern exakt das
 *    Verfahren, mit dem der Plan entsteht.
 *
 * Das Startvolumen wird dabei aus dem Plan selbst zurueckgelesen (Woche 0 ist
 * immer eine Aufbauwoche mit Fortschritt 0, ihr Ziel ist das gerundete
 * Startvolumen). Damit braucht die Pruefung keine [FitnessAssessment] und
 * funktioniert auch fuer einen laengst gespeicherten Plan.
 */
fun assessPlanFeasibility(plan: TrainingPlan): PlanFeasibility {
    val goalKm = plan.goal.distanceKm
    val longest = plan.weeks
        .flatMap { it.sessions }
        .filterNot { it.isEvent }
        .maxOfOrNull { it.targetKm }
        ?: 0

    if (!goalKm.isFinite() || goalKm <= 0) {
        return PlanFeasibility(longest, goalKm, 0.0, true, null, null, null)
    }

    val coverage = longest / goalKm
    if (coverage >= minLongestRideShare) {
        return PlanFeasibility(longest, goalKm, coverage, true, null, null, null)
    }

    val startKm = plan.weeks.firstOrNull()?.targetKm?.toDouble() ?: 0.0
    val suggestedDistance = max(5, round5(longest / minLongestRideShare))
    val suggestedWeeks = weeksForFeasibleGoal(plan.goal, plan.level, startKm)

    val percent = dartRound(coverage * 100).toInt()
    val goalText = dartRound(goalKm).toInt()
    val outlook = when {
        suggestedWeeks != null && suggestedWeeks > plan.weeks.size ->
            "Für die vollen $goalText km brauchst du von deinem heutigen Umfang aus rund " +
                "$suggestedWeeks Wochen."

        suggestedWeeks != null ->
            "Mit einem etwas anderen Zuschnitt wären die $goalText km in $suggestedWeeks " +
                "Wochen erreichbar."

        else ->
            "Für die vollen $goalText km reicht selbst ein Jahr Vorbereitung von deinem " +
                "heutigen Umfang aus nicht — bau erst über eine Zwischendistanz auf."
    }

    return PlanFeasibility(
        longestRideKm = longest,
        goalDistanceKm = goalKm,
        coverage = coverage,
        feasible = false,
        suggestedDistanceKm = suggestedDistance,
        suggestedWeeks = suggestedWeeks,
        message = "Die längste Fahrt in diesem Plan sind $longest km – nur $percent % deiner " +
            "Zieldistanz von $goalText km. Realistisch trägt dieser Plan ein Ziel um " +
            "$suggestedDistance km. $outlook",
    )
}

/**
 * Die kuerzeste Laufzeit in Wochen, nach der ein Plan fuer [goal] die
 * [minLongestRideShare]-Schwelle nimmt — `null`, wenn selbst [MAX_WEEKS] nicht
 * reichen.
 *
 * Rechnet den echten Aufbau durch ([planWeekVolumes] + [buildSessions]) statt
 * eine Naeherungsformel: Rampengrenze, Erholungswochen und Anteile der
 * Schluesseleinheit greifen ineinander, und eine zweite, „ungefaehre" Formel
 * daneben waere genau die Sorte Wahrheit, die irgendwann von der ersten
 * abweicht.
 */
fun weeksForFeasibleGoal(goal: Goal, level: FitnessLevel, startKm: Double): Int? {
    if (!goal.distanceKm.isFinite() || goal.distanceKm <= 0 || startKm <= 0) {
        return null
    }
    val peakKm = peakKmFor(goal, startKm)
    val eventWeekKm = zielwocheSessions(goal).sumOf { it.targetKm }

    for (weekCount in MIN_WEEKS..MAX_WEEKS) {
        val kinds = planWeekKinds(weekCount)
        val volumes = planWeekVolumes(kinds, startKm, peakKm, eventWeekKm)
        val longest = kinds.indices
            .flatMap { buildSessions(kinds[it], level, volumes[it], goal) }
            .filterNot { it.isEvent }
            .maxOfOrNull { it.targetKm }
            ?: 0
        if (longest / goal.distanceKm >= minLongestRideShare) {
            return weekCount
        }
    }
    return null
}

/**
 * Laedt den gespeicherten Plan; `null`, wenn keiner existiert oder die Daten
 * unbrauchbar sind.
 */
fun loadPlan(store: TrainingPlanStore): TrainingPlan? = try {
    val raw = store.read()
    if (raw == null) {
        null
    } else {
        val parsed = Json.parseToJsonElement(raw)
        if (parsed !is JsonObject || parsed["weeks"] !is JsonArray) {
            null
        } else {
            TrainingPlan.fromJson(parsed)
        }
    }
} catch (_: Throwable) {
    // Speicher nicht verfuegbar oder Daten defekt – kein Plan.
    null
}

/** Speichert den Plan; `null` entfernt den gespeicherten Plan. */
fun savePlan(store: TrainingPlanStore, plan: TrainingPlan?) {
    try {
        if (plan == null) {
            store.remove()
            return
        }
        store.write(plan.toJson().toString())
    } catch (_: Throwable) {
        // Speicher nicht verfuegbar – Plan bleibt nur im Arbeitsspeicher.
    }
}

/** Index der aktuellen Planwoche; -1 vor Planbeginn, letzte Woche nach Planende. */
fun currentWeekIndex(plan: TrainingPlan, now: Long? = null): Int {
    val nowMs = now ?: System.currentTimeMillis()
    val weeks = plan.weeks

    if (weeks.isEmpty()) {
        return -1
    }
    if (nowMs < weeks.first().start) {
        return -1
    }

    for (week in weeks) {
        if (nowMs >= week.start && nowMs < week.end) {
            return week.index
        }
    }

    return weeks.size - 1
}

/**
 * Die fuer **heute** geplanten Einheiten — leer, wenn heute ein Ruhetag ist.
 *
 * Ergaenzung fuer die Startseite „Heute" (`ui/today/TodayScreen.kt`), die genau
 * eine Frage beantworten muss: „Was steht heute an?". Die Zuordnung
 * Zeitpunkt → Wochentagskuerzel liegt bewusst hier und nicht in der
 * Oberflaeche: [TrainingSession.day] traegt die Kuerzel „Mo"…„So", die
 * [generatePlan] aus derselben privaten Tabelle erzeugt. Ein zweites,
 * handgeschriebenes Kuerzel-Mapping im UI wuerde beim naechsten Sprachwechsel
 * oder Umbenennen lautlos auseinanderlaufen.
 *
 * Ausserhalb der Planlaufzeit ist die Liste leer: Vor Planbeginn liefert
 * [currentWeekIndex] `-1`, nach Planende klemmt es auf die letzte Woche — deren
 * Einheiten liegen dann aber in der Vergangenheit und sind kein Tagesprogramm
 * mehr. Beides wird hier abgefangen.
 */
fun sessionsForDay(plan: TrainingPlan, now: Long? = null): List<TrainingSession> {
    val nowMs = now ?: System.currentTimeMillis()
    val index = currentWeekIndex(plan, nowMs)
    if (index < 0) {
        return emptyList()
    }
    val week = plan.weeks.getOrNull(index) ?: return emptyList()
    if (nowMs < week.start || nowMs >= week.end) {
        return emptyList()
    }
    val day = weekdays[weekdayIndex(nowMs)]
    return week.sessions.filter { it.day == day }
}

/**
 * Summiert die tatsaechlich gefahrenen Kilometer im Zeitraum [start, end).
 *
 * „Tatsaechlich gefahren" ist woertlich gemeint: Gespeicherte **Planungen**
 * ([Ride.planned]) zaehlen nicht mit. Vorher taten sie es — „Als Tour
 * speichern" auf der Karte legte eine gewoehnliche Tour an, und der
 * Wochenfortschritt sprang, ohne dass jemand im Sattel sass.
 */
fun weekKm(week: TrainingWeek, rides: List<Ride>): Double {
    var total = 0.0
    for (ride in riddenRides(rides)) {
        if (ride.createdAt >= week.start && ride.createdAt < week.end) {
            total += ride.stats.distanceKm
        }
    }
    return dartRound1(total)
}
