package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.PLAN_WEEKDAY_CODES
import de.trailscape.core.i18n.SessionTextKey
import de.trailscape.core.i18n.sessionTitle
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * # Plan-Rezept ⇄ Trainingsplan
 *
 * Import: [planFromRecipe] / [planFromWorkout] machen aus einem gelesenen
 * Rezept einen [TrainingPlan] mit den Wochen ab einem gewaehlten Montag.
 * Export: [recipeFromPlan] / [recipeFromSession] nehmen aus einem Plan **nur**
 * Struktur und Intensitaet — nie Ziel, Datum, Name, Praeferenzen, Tempo,
 * Beschreibungstexte oder das Zielevent (siehe `docs/plan-rezepte.md`,
 * „Was nie in der Datei steht").
 *
 * ## Umrechnung auf die eigenen Werte
 * Ein Rezept kennt nur Prozent der FTP. Der Plan speichert dasselbe; Watt
 * entstehen erst bei der Anzeige aus der FTP des Empfaengers
 * ([recipePowerRangeW], Vorschau in [previewRecipe]). Aendert sich die FTP,
 * stimmen die Watt sofort mit.
 */

/** Dauer in Minuten nach der Intensitaet der Abschnitte — fuer [SessionIntensity] und Last. */
internal const val RECIPE_HARD_MIN_FTP = 0.88
internal const val RECIPE_HARD_MIN_MINUTES = 10
internal const val RECIPE_EASY_MAX_FTP = 0.60

/** Intensitaet einer Rezepteinheit: hart ab 10 min im Sweet-Spot-Bereich oder darueber, locker nur wenn alles ruhig ist. */
fun recipeSessionIntensity(session: RecipeSession): SessionIntensity {
    val hardMinutes = session.segments
        .filter { it.midFtp >= RECIPE_HARD_MIN_FTP }
        .sumOf { it.repeat * it.minutes }
    return when {
        hardMinutes >= RECIPE_HARD_MIN_MINUTES -> SessionIntensity.HART
        session.segments.all { it.midFtp <= RECIPE_EASY_MAX_FTP } -> SessionIntensity.LOCKER
        else -> SessionIntensity.GRUNDLAGE
    }
}

/** Abschnitte fuer das Lastmodell: Belastung mit der Bereichsmitte als IF, Erholung wie Pausen im Plan. */
internal fun recipeLoadSegments(session: RecipeSession): List<LoadSegment> = session.segments.flatMap { seg ->
    buildList {
        add(LoadSegment((seg.repeat * seg.minutes).toDouble(), seg.midFtp))
        if (seg.repeat > 1 && seg.recoveryMinutes > 0) {
            add(LoadSegment(((seg.repeat - 1) * seg.recoveryMinutes).toDouble(), EASY_SEGMENT_IF))
        }
    }
}

/** Leistungsbereich in Watt bei [ftpW]; `null` ohne verwertbare FTP. */
fun recipePowerRangeW(segment: RecipeSegment, ftpW: Int?): Pair<Int, Int>? {
    val ftp = ftpW?.takeIf { it > 0 } ?: return null
    return Pair(
        (ftp * segment.ftpLowPercent / 100.0).roundToInt(),
        (ftp * segment.ftpHighPercent / 100.0).roundToInt(),
    )
}

/**
 * Die Struktur einer Einheit als kurze, sprachneutrale Zeile:
 * `15 min 56–75 % FTP · 3 × 12 min 88–94 % FTP (211–226 W), 5 min Pause · 10 min 40–55 % FTP`.
 * Mit [ftpW] kommen die Watt der eigenen FTP in Klammern dazu; [pauseWord] ist
 * das Wort fuer die Erholung zwischen Wiederholungen (Sprache der Oberflaeche).
 */
fun describeRecipeSegments(segments: List<RecipeSegment>, ftpW: Int?, pauseWord: String): String =
    segments.joinToString(" · ") { seg ->
        val percent = if (seg.ftpLowPercent == seg.ftpHighPercent) "${seg.ftpLowPercent} %" else "${seg.ftpLowPercent}–${seg.ftpHighPercent} %"
        val watts = recipePowerRangeW(seg, ftpW)?.let { (lo, hi) ->
            if (lo == hi) " ($lo W)" else " ($lo–$hi W)"
        }.orEmpty()
        val head = if (seg.repeat > 1) "${seg.repeat} × ${seg.minutes} min" else "${seg.minutes} min"
        val pause = if (seg.repeat > 1 && seg.recoveryMinutes > 0) ", ${seg.recoveryMinutes} min $pauseWord" else ""
        val label = seg.label?.let { "$it: " }.orEmpty()
        "$label$head $percent FTP$watts$pause"
    }

// ---------------------------------------------------------------------------
// Rezept → Plan
// ---------------------------------------------------------------------------

/**
 * Die Planeinheit zu einer Rezepteinheit. Beschreibung = Hinweis des Rezepts
 * plus die Struktur in Prozent der FTP (Watt stehen bewusst nicht im
 * gespeicherten Text: Sie wuerden bei einer neuen FTP veralten).
 */
internal fun sessionFromRecipe(
    session: RecipeSession,
    dayIndex: Int,
    speedKmh: Double,
    pauseWord: String,
): TrainingSession {
    val minutes = session.totalMinutes
    val structure = describeRecipeSegments(session.segments, ftpW = null, pauseWord = pauseWord)
    val description = listOfNotNull(session.notes, structure).joinToString("\n")
    return TrainingSession(
        day = PLAN_WEEKDAY_CODES[dayIndex],
        title = session.title,
        description = description,
        targetKm = max(1, (minutes / 60.0 * speedKmh).roundToInt()),
        intensity = recipeSessionIntensity(session),
        durationMin = minutes,
        isEvent = false,
        targetLoad = max(1.0, dartRound(segmentsLoad(recipeLoadSegments(session)))),
        textKey = null,
        textArgs = emptyList(),
        fromRecipe = true,
    )
}

private fun mondayMs(date: LocalDate): Long = dartEpochMs(date.atStartOfDay())

/** Der Montag der Woche von [date] — und, falls das schon vorbei ist, nichts weiter: der Aufrufer waehlt. */
fun mondayOfWeek(date: LocalDate): LocalDate = date.with(DayOfWeek.MONDAY)

/** Der naechste Montag nach [today] (heute, wenn heute Montag ist). */
fun nextMondayOnOrAfter(today: LocalDate): LocalDate =
    if (today.dayOfWeek == DayOfWeek.MONDAY) today else today.with(java.time.temporal.TemporalAdjusters.next(DayOfWeek.MONDAY))

/**
 * Baut den Plan aus einem Plan-Rezept; Woche 0 beginnt am [firstMonday].
 *
 * Das Ziel ist ein [GoalType.FITNESS]-Block mit dem Rezepttitel als Namen und
 * dem letzten Tag des Plans als Datum; es gibt kein Event und keine Prognose.
 * [speedKmh] ist nur die Umrechnung von Minuten in Kilometer fuer die
 * Routenwahl (`null` = [fallbackGravelSpeedKmh]).
 */
fun planFromRecipe(
    recipe: PlanRecipePlan,
    firstMonday: LocalDate,
    nowMs: Long,
    speedKmh: Double? = null,
    pauseWord: String = "rest",
): TrainingPlan {
    val speed = speedKmh?.takeIf { it.isFinite() && it > 0 } ?: fallbackGravelSpeedKmh
    val weeks = recipe.weeks.mapIndexed { index, week ->
        val monday = firstMonday.plusWeeks(index.toLong())
        val sessions = week.sessions
            .sortedBy { it.day ?: 0 }
            .map { sessionFromRecipe(it, it.day ?: 0, speed, pauseWord) }
        TrainingWeek(
            index = index,
            start = mondayMs(monday),
            end = mondayMs(monday.plusWeeks(1)),
            kind = week.kind.weekKind,
            targetKm = sessions.sumOf { it.targetKm },
            sessions = sessions,
        )
    }
    val lastDay = firstMonday.plusWeeks(recipe.weeks.size.toLong()).minusDays(1)
    return TrainingPlan(
        createdAt = nowMs,
        goal = Goal(
            name = recipe.title,
            distanceKm = 0.0,
            ascentM = null,
            date = mondayMs(lastDay),
            targetDurationMin = null,
            type = GoalType.FITNESS,
        ),
        level = FitnessLevel.FORTGESCHRITTEN,
        weeks = weeks,
        preferences = null,
        planSpeedKmh = speedKmh?.takeIf { it.isFinite() && it > 0 },
        recipe = PlanRecipeOrigin(recipe.title, recipe.author),
    )
}

/**
 * Eine einzelne Einheit als Ein-Wochen-Plan (wenn es noch keinen Plan gibt)
 * am Tag [dayIndex] der Woche von [today].
 */
fun planFromWorkout(
    recipe: PlanRecipeWorkout,
    dayIndex: Int,
    today: LocalDate,
    nowMs: Long,
    speedKmh: Double? = null,
    pauseWord: String = "rest",
): TrainingPlan = planFromRecipe(
    recipe = PlanRecipePlan(
        title = recipe.title,
        author = recipe.author,
        notes = recipe.notes,
        weeks = listOf(RecipeWeek(RecipeWeekKind.BUILD, listOf(recipe.session.copy(day = dayIndex.coerceIn(0, 6))))),
    ),
    firstMonday = mondayOfWeek(today),
    nowMs = nowMs,
    speedKmh = speedKmh,
    pauseWord = pauseWord,
)

/**
 * Traegt eine einzelne Einheit in die Woche von [today] eines bestehenden Plans
 * ein (am Tag [dayIndex]). Liegt [today] ausserhalb des Plans, kommt sie in die
 * naechste noch offene Woche, sonst in die letzte. Gibt `null` zurueck, wenn
 * der Plan keine Wochen hat.
 */
fun planWithInsertedWorkout(
    plan: TrainingPlan,
    recipe: PlanRecipeWorkout,
    dayIndex: Int,
    nowMs: Long,
    pauseWord: String = "rest",
): TrainingPlan? {
    if (plan.weeks.isEmpty()) return null
    val target = plan.weeks.firstOrNull { nowMs >= it.start && nowMs < it.end }
        ?: plan.weeks.firstOrNull { it.start > nowMs }
        ?: plan.weeks.last()
    val speed = plan.planSpeedKmh ?: fallbackGravelSpeedKmh
    val added = sessionFromRecipe(recipe.session, dayIndex.coerceIn(0, 6), speed, pauseWord)
    val sessions = (target.sessions + added).sortedBy { PLAN_WEEKDAY_CODES.indexOf(it.day).let { i -> if (i < 0) 7 else i } }
    val updated = target.copy(targetKm = sessions.sumOf { it.targetKm }, sessions = sessions)
    return plan.copy(weeks = plan.weeks.map { if (it.index == target.index) updated else it })
}

// ---------------------------------------------------------------------------
// Vorschau
// ---------------------------------------------------------------------------

/** Eine Einheit in der Vorschau (Tag, Titel, Dauer, Struktur in Prozent und — mit FTP — Watt). */
data class RecipeSessionPreview(
    val day: Int?,
    val title: String,
    val minutes: Int,
    val structure: String,
    val intensity: SessionIntensity,
)

/** Was die Importvorschau zeigt, bevor etwas uebernommen wird. */
data class PlanRecipePreview(
    val title: String,
    val author: String?,
    val notes: String?,
    val isWorkout: Boolean,
    val weekCount: Int,
    val sessionCount: Int,
    /** Kleinste und groesste Zahl Einheiten je Woche (0 fuer eine Ruhewoche). */
    val minSessionsPerWeek: Int,
    val maxSessionsPerWeek: Int,
    val totalMinutes: Int,
    /** Dauer der laengsten Woche in Minuten. */
    val peakWeekMinutes: Int,
    /** Einheiten der ersten Woche (bei einer einzelnen Einheit: diese eine). */
    val firstWeek: List<RecipeSessionPreview>,
    /** FTP, auf die umgerechnet wurde; `null` = Anzeige nur in Prozent. */
    val ftpW: Int?,
)

/** Die Vorschau eines Rezepts, auf [ftpW] (FTP des Empfaengers) umgerechnet. */
fun previewRecipe(recipe: PlanRecipe, ftpW: Int?, pauseWord: String): PlanRecipePreview {
    val ftp = ftpW?.takeIf { it > 0 }
    fun sessionPreview(s: RecipeSession) = RecipeSessionPreview(
        day = s.day,
        title = s.title,
        minutes = s.totalMinutes,
        structure = describeRecipeSegments(s.segments, ftp, pauseWord),
        intensity = recipeSessionIntensity(s),
    )
    return when (recipe) {
        is PlanRecipePlan -> {
            val counts = recipe.weeks.map { it.sessions.size }
            val minutesPerWeek = recipe.weeks.map { w -> w.sessions.sumOf { it.totalMinutes } }
            PlanRecipePreview(
                title = recipe.title,
                author = recipe.author,
                notes = recipe.notes,
                isWorkout = false,
                weekCount = recipe.weeks.size,
                sessionCount = counts.sum(),
                minSessionsPerWeek = counts.minOrNull() ?: 0,
                maxSessionsPerWeek = counts.maxOrNull() ?: 0,
                totalMinutes = minutesPerWeek.sum(),
                peakWeekMinutes = minutesPerWeek.maxOrNull() ?: 0,
                firstWeek = recipe.weeks.first().sessions.sortedBy { it.day ?: 0 }.map(::sessionPreview),
                ftpW = ftp,
            )
        }
        is PlanRecipeWorkout -> PlanRecipePreview(
            title = recipe.title,
            author = recipe.author,
            notes = recipe.notes,
            isWorkout = true,
            weekCount = 1,
            sessionCount = 1,
            minSessionsPerWeek = 1,
            maxSessionsPerWeek = 1,
            totalMinutes = recipe.session.totalMinutes,
            peakWeekMinutes = recipe.session.totalMinutes,
            firstWeek = listOf(sessionPreview(recipe.session)),
            ftpW = ftp,
        )
    }
}

// ---------------------------------------------------------------------------
// Plan → Rezept
// ---------------------------------------------------------------------------

private fun uniform(minutes: Int, zone: TargetZone) = listOf(RecipeSegment.inZone(minutes, zone))

private fun uniformZone(intensity: SessionIntensity): TargetZone = when (intensity) {
    SessionIntensity.LOCKER -> TargetZone.RECOVERY
    SessionIntensity.GRUNDLAGE -> TargetZone.ENDURANCE
    SessionIntensity.HART -> TargetZone.THRESHOLD
}

/** Gesamtdauer einer Planeinheit; fehlt sie (Altplan), aus den Kilometern geschaetzt. */
private fun sessionMinutes(session: TrainingSession): Int {
    session.durationMin?.takeIf { it > 0 }?.let { return it.coerceAtMost(MAX_SESSION_MINUTES) }
    if (session.targetKm <= 0) return 0
    return (session.targetKm / fallbackGravelSpeedKmh * 60).roundToInt().coerceIn(1, MAX_SESSION_MINUTES)
}

/**
 * Die Abschnitte einer Planeinheit — aus Struktur ([TrainingSession.textKey]
 * und -args) und, wo es keine gibt, aus Dauer und Intensitaet. `null` fuer das
 * Zielevent und fuer Einheiten ohne Dauer.
 */
internal fun recipeSegmentsOf(session: TrainingSession): List<RecipeSegment>? {
    if (session.isEvent) return null
    val total = sessionMinutes(session)
    if (total <= 0) return null
    val args = session.textArgs
    val structured: List<RecipeSegment>? = when (session.textKey) {
        SessionTextKey.WORKOUT -> parseWorkoutArgs(args)?.let { shape ->
            listOf(
                RecipeSegment.inZone(WORKOUT_WARMUP_MIN, TargetZone.ENDURANCE),
                RecipeSegment.inZone(shape.workMin, shape.kind.zone, shape.reps, shape.restMin),
                RecipeSegment.inZone(WORKOUT_COOLDOWN_MIN, TargetZone.RECOVERY),
            )
        }
        SessionTextKey.INTERVALS -> if (args.size > INTERVAL_ARG_EFFORT) {
            val warm = args[0]
            val reps = args[1]
            val work = args[2]
            val rest = args[3]
            val cool = args[4]
            if (warm in 1..MAX_SEGMENT_MINUTES && reps in 1..MAX_SEGMENT_REPEAT && work in 1..MAX_SEGMENT_MINUTES &&
                rest in 0..MAX_SEGMENT_RECOVERY_MIN && cool in 1..MAX_SEGMENT_MINUTES
            ) {
                val zone = if (args[INTERVAL_ARG_EFFORT] >= 1) TargetZone.THRESHOLD else TargetZone.SWEET_SPOT
                listOf(
                    RecipeSegment.inZone(warm, TargetZone.ENDURANCE),
                    RecipeSegment.inZone(work, zone, reps, rest),
                    RecipeSegment.inZone(cool, TargetZone.RECOVERY),
                )
            } else {
                null
            }
        } else {
            null
        }
        SessionTextKey.FTP_TEST -> listOf(
            RecipeSegment.inZone(args.getOrElse(FtpTestArg.WARMUP) { FTP_TEST_WARMUP_MIN }, TargetZone.ENDURANCE),
            RecipeSegment(args.getOrElse(FtpTestArg.TEST) { FTP_TEST_MIN }, 100, 110),
            RecipeSegment.inZone(args.getOrElse(FtpTestArg.COOLDOWN) { FTP_TEST_COOLDOWN_MIN }, TargetZone.RECOVERY),
        )
        SessionTextKey.BENCHMARK_RIDE -> listOf(
            RecipeSegment.inZone(WORKOUT_WARMUP_MIN, TargetZone.ENDURANCE),
            RecipeSegment(max(1, total - WORKOUT_WARMUP_MIN), 80, 90),
        )
        SessionTextKey.LONG_RIDE -> {
            val blocks = args.getOrElse(LongRideArg.BLOCKS) { 0 }
            val blockMin = args.getOrElse(LongRideArg.BLOCK_MIN) { 0 }
            val paceMin = blocks * blockMin
            if (blocks in 1..MAX_SEGMENT_REPEAT && blockMin in 1..MAX_SEGMENT_MINUTES && paceMin < total) {
                val rest = total - paceMin
                val before = rest / 2
                listOfNotNull(
                    RecipeSegment.inZone(before, TargetZone.ENDURANCE).takeIf { before > 0 },
                    RecipeSegment.inZone(blockMin, TargetZone.TEMPO, repeat = blocks, recoveryMinutes = 0),
                    RecipeSegment.inZone(rest - before, TargetZone.ENDURANCE).takeIf { rest - before > 0 },
                )
            } else {
                null
            }
        }
        else -> null
    }
    val segments = structured ?: uniform(total.coerceAtMost(MAX_SEGMENT_MINUTES), uniformZone(session.intensity))
    // Ueber die Grenze hinaus waere die Datei beim Wiedereinlesen ungueltig.
    return segments.takeIf { s -> s.sumOf { it.totalMinutes } <= MAX_SESSION_MINUTES }
}

private fun String.clip(max: Int): String = trim().filter { it == '\n' || !it.isISOControl() }.take(max).trim()

private fun recipeSessionOf(session: TrainingSession, texts: CoreTexts, includeDay: Boolean): RecipeSession? {
    val segments = recipeSegmentsOf(session) ?: return null
    val dayIndex = PLAN_WEEKDAY_CODES.indexOf(session.day)
    if (includeDay && dayIndex < 0) return null
    val title = sessionTitle(session, texts).clip(MAX_TITLE_CHARS).ifEmpty { return null }
    return RecipeSession(
        day = if (includeDay) dayIndex else null,
        title = title,
        notes = null,
        segments = segments,
    )
}

/**
 * Das Rezept zu einem ganzen Plan; `null`, wenn keine Einheit uebrig bleibt.
 *
 * Mitgenommen werden Wochenart, Wochentag, Titel und Struktur der Einheiten
 * (zonenbenannt, wo die App ihre Zonen kennt). **Nicht** mitgenommen:
 * Zielname, Zieldatum, Zielzeit, Zielevent, Praeferenzen, Tempo, Planbeginn
 * und die Beschreibungstexte (sie koennen persoenliche Zahlen enthalten).
 */
fun recipeFromPlan(plan: TrainingPlan, title: String, author: String?, texts: CoreTexts): PlanRecipePlan? {
    val weeks = plan.weeks.take(MAX_RECIPE_WEEKS).map { week ->
        RecipeWeek(
            kind = RecipeWeekKind.of(week.kind),
            sessions = week.sessions
                .mapNotNull { recipeSessionOf(it, texts, includeDay = true) }
                .sortedBy { it.day ?: 0 }
                .take(MAX_RECIPE_SESSIONS_PER_WEEK),
        )
    }
    if (weeks.all { it.sessions.isEmpty() }) return null
    return PlanRecipePlan(
        title = title.clip(MAX_TITLE_CHARS).ifEmpty { "Trainingsplan" },
        author = author?.clip(MAX_AUTHOR_CHARS)?.ifEmpty { null },
        notes = null,
        weeks = weeks,
    )
}

/** Das Rezept zu einer einzelnen Einheit; `null` beim Zielevent oder ohne Dauer. */
fun recipeFromSession(session: TrainingSession, title: String?, author: String?, texts: CoreTexts): PlanRecipeWorkout? {
    val recipeSession = recipeSessionOf(session, texts, includeDay = false) ?: return null
    return PlanRecipeWorkout(
        title = (title?.clip(MAX_TITLE_CHARS)?.ifEmpty { null } ?: recipeSession.title),
        author = author?.clip(MAX_AUTHOR_CHARS)?.ifEmpty { null },
        notes = null,
        session = recipeSession,
    )
}
