package de.trailscape.core

/**
 * # Planeinheiten in Klartext
 *
 * Die Titel, die [generatePlan] schreibt („GA1", „GA1 kompensatorisch",
 * „Lockere Ausfahrt GA1"), sind Trainerlatein und bleiben trotzdem, wie sie
 * sind: Sie stehen im Plan-JSON, das mit Web-App und Sync-Server abgestimmt
 * ist. Die Wochenliste des Trainings-Tabs (Redesign „Klartext",
 * `docs/design/prototyp-klartext.html`) zeigt stattdessen die Einteilung des
 * Woerterbuchs dort: **locker / hart**, eine **lange Fahrt**, das **Rennen** —
 * jeweils mit den Kilometern, weil die Zahl das ist, wonach man die Runde
 * plant.
 *
 * Die Ableitung laeuft ueber die Felder der Einheit ([TrainingSession.intensity],
 * [TrainingSession.isEvent]); nur die lange Fahrt wird am Titel erkannt, weil
 * es fuer sie kein eigenes Feld gibt — alle Planversionen nennen sie
 * „Lange Tour".
 */

/** Ob [session] die lange Fahrt der Woche ist. */
fun isLongRideSession(session: TrainingSession): Boolean =
    !session.isEvent && session.title.lowercase().startsWith("lange")

/** Klartext-Titel, z. B. „Locker 45 km", „Lange Fahrt 80 km", „Hart 30 km". */
fun plainSessionTitle(session: TrainingSession): String {
    val km = "${session.targetKm} km"
    return when {
        session.isEvent -> "Rennen $km"
        isLongRideSession(session) -> "Lange Fahrt $km"
        session.intensity == SessionIntensity.HART -> "Hart $km"
        else -> "Locker $km"
    }
}

/**
 * Ein kurzer Satz, **wie** die Einheit gefahren wird — ohne Zonen und
 * Kuerzel. [goal] ergaenzt bei der langen Fahrt die Hoehenmeter-Erinnerung,
 * wenn das Ziel huegelig ist (ab 300 Hm).
 */
fun plainSessionHint(session: TrainingSession, goal: Goal? = null): String = when {
    session.isEvent -> "dein Ziel"
    isLongRideSession(session) ->
        if ((goal?.ascentM ?: 0.0) >= 300) {
            "gleichmäßig, mit Höhenmetern wie im Rennen"
        } else {
            "gleichmäßig, lange im Sattel"
        }
    session.intensity == SessionIntensity.HART -> "mit harten Abschnitten, dazwischen locker"
    session.intensity == SessionIntensity.LOCKER -> "ganz ruhig, für frische Beine"
    else -> "ruhig, du kannst dich dabei unterhalten"
}
