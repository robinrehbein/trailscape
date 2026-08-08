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
private const val PEAK_CAP_FACTOR = 2.2
private const val ACTIVATION_KM = 15
private const val CLIMBING_HINT =
    " Baue dabei bewusst Anstiege ein, um dich an die Höhenmeter des Ziels zu gewöhnen."
private const val CLIMB_HINT_THRESHOLD_M = 1000.0

private const val WEEK_MS = 7L * 24 * 60 * 60 * 1000

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

private fun sessionKm(weekKmTarget: Int, share: Double): Int =
    max(1, dartRound(weekKmTarget * share).toInt())

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
        return listOf(
            TrainingSession(
                day = "Di",
                title = "Lockere Ausfahrt",
                description = "Entspannt rollen, kleine Gänge und hohe Trittfrequenz – diese " +
                    "Woche dient ausschließlich der Erholung.",
                targetKm = sessionKm(targetKm, 0.5),
            ),
            TrainingSession(
                day = "Sa",
                title = "Ruhige Runde",
                description = "Gemütliche Ausfahrt ohne Leistungsdruck, halte den Puls " +
                    "durchgehend im niedrigen Bereich.",
                targetKm = sessionKm(targetKm, 0.5),
            ),
        )
    }

    if (kind == WeekKind.TAPER) {
        return listOf(
            TrainingSession(
                day = "Di",
                title = "Locker mit Antritten",
                description = "Locker rollen und dabei 3 kurze Antritte über je 30 Sekunden " +
                    "einstreuen, um spritzig zu bleiben.",
                targetKm = sessionKm(targetKm, 0.55),
            ),
            TrainingSession(
                day = "Do",
                title = "Kurze lockere Ausfahrt",
                description = "Kurz und ruhig fahren, danach Material checken und die Beine " +
                    "bewusst schonen.",
                targetKm = sessionKm(targetKm, 0.45),
            ),
        )
    }

    return aufbauSessions(level, targetKm, goal)
}

private fun aufbauSessions(
    level: FitnessLevel,
    targetKm: Int,
    goal: Goal,
): List<TrainingSession> {
    if (level == FitnessLevel.EINSTEIGER) {
        val withRecovery = targetKm >= 60
        val sessions = mutableListOf(
            TrainingSession(
                day = "Di",
                title = "Lockere Ausfahrt GA1",
                description = "Ruhiges Grundlagentempo – du solltest dich während der gesamten " +
                    "Fahrt unterhalten können.",
                targetKm = sessionKm(targetKm, if (withRecovery) 0.3 else 0.4),
            ),
            TrainingSession(
                day = "Sa",
                title = "Lange Tour",
                description = longTourDescription(goal),
                targetKm = sessionKm(targetKm, if (withRecovery) 0.5 else 0.6),
            ),
        )
        if (withRecovery) {
            sessions.add(
                TrainingSession(
                    day = "So",
                    title = "Regeneration locker",
                    description = "Kurze Regenerationsrunde im leichten Gang, bewusst niedrige " +
                        "Intensität für frische Beine.",
                    targetKm = sessionKm(targetKm, 0.2),
                ),
            )
        }
        return sessions
    }

    if (level == FitnessLevel.FORTGESCHRITTEN) {
        return listOf(
            TrainingSession(
                day = "Di",
                title = "GA1",
                description = "Lockere Grundlageneinheit zum Auffüllen des Wochenvolumens, Puls " +
                    "konstant im GA1-Bereich halten.",
                targetKm = sessionKm(targetKm, 0.25),
            ),
            TrainingSession(
                day = "Do",
                title = "Intervalle",
                description = "Nach 20 Minuten Einfahren 4×8 Minuten zügig im Schwellenbereich, " +
                    "dazwischen 4 Minuten locker rollen.",
                targetKm = sessionKm(targetKm, 0.2),
            ),
            TrainingSession(
                day = "Sa",
                title = "Lange Tour",
                description = longTourDescription(goal),
                targetKm = sessionKm(targetKm, 0.55),
            ),
        )
    }

    return listOf(
        TrainingSession(
            day = "Di",
            title = "GA1",
            description = "Ruhige Grundlageneinheit, gleichmäßige Belastung ohne Spitzen und " +
                "ohne Sprints.",
            targetKm = sessionKm(targetKm, 0.2),
        ),
        TrainingSession(
            day = "Mi",
            title = "Intervalle",
            description = "Nach dem Einfahren 5×6 Minuten hart an der Schwelle mit je 3 Minuten " +
                "lockerer Pause dazwischen.",
            targetKm = sessionKm(targetKm, 0.2),
        ),
        TrainingSession(
            day = "Sa",
            title = "Lange Tour",
            description = longTourDescription(goal),
            targetKm = sessionKm(targetKm, 0.45),
        ),
        TrainingSession(
            day = "So",
            title = "GA1 kompensatorisch",
            description = "Kompensationsrunde mit hoher Trittfrequenz, um die Beine nach der " +
                "langen Tour wieder locker zu fahren.",
            targetKm = sessionKm(targetKm, 0.15),
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
    )

    val activationDay = if (eventIndex > 1) "Di" else if (eventIndex == 1) "Mo" else null
    if (activationDay == null) {
        return listOf(eventSession)
    }

    return listOf(
        TrainingSession(
            day = activationDay,
            title = "Aktivierung locker",
            description = "Kurze lockere Runde mit ein paar Antritten, danach Rad und " +
                "Verpflegung für den Zieltag vorbereiten.",
            targetKm = ACTIVATION_KM,
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

/**
 * Erzeugt einen Trainingsplan vom Montag der aktuellen Woche bis zur
 * Zielwoche.
 *
 * Wirft [IllegalArgumentException] (Darts `ArgumentError`), wenn das Ziel
 * weniger als 3 oder mehr als 52 Wochen entfernt liegt.
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
    val startKm = max(assessment.weeklyKm, levelBaseKm.getValue(level))
    val peakKm = min(
        max(goal.distanceKm * PEAK_DISTANCE_FACTOR, startKm),
        startKm * PEAK_CAP_FACTOR,
    )

    val kinds = planWeekKinds(weekCount)
    val buildCount = kinds.count { it == WeekKind.AUFBAU }

    val weeks = mutableListOf<TrainingWeek>()
    var buildSeen = 0
    var previousKm = startKm

    for (i in 0 until weekCount) {
        val kind = kinds[i]
        val targetKm: Int

        if (kind == WeekKind.AUFBAU) {
            val progress = if (buildCount > 1) buildSeen.toDouble() / (buildCount - 1) else 1.0
            targetKm = round5(startKm + (peakKm - startKm) * progress)
            buildSeen += 1
        } else if (kind == WeekKind.ERHOLUNG) {
            targetKm = round5(previousKm * RECOVERY_FACTOR)
        } else if (kind == WeekKind.TAPER) {
            targetKm = round5(peakKm * TAPER_FACTOR)
        } else {
            targetKm = zielwocheSessions(goal).sumOf { it.targetKm }
        }

        previousKm = targetKm.toDouble()

        weeks.add(
            TrainingWeek(
                index = i,
                start = addWeeks(firstMonday, i),
                end = addWeeks(firstMonday, i + 1),
                kind = kind,
                targetKm = targetKm,
                sessions = buildSessions(kind, level, targetKm, goal),
            ),
        )
    }

    return TrainingPlan(
        createdAt = nowMs,
        goal = goal,
        level = level,
        weeks = weeks,
    )
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

/** Summiert die tatsaechlich gefahrenen Kilometer im Zeitraum [start, end). */
fun weekKm(week: TrainingWeek, rides: List<Ride>): Double {
    var total = 0.0
    for (ride in rides) {
        if (ride.createdAt >= week.start && ride.createdAt < week.end) {
            total += ride.stats.distanceKm
        }
    }
    return dartRound1(total)
}
