package de.trailscape.core

import de.trailscape.core.i18n.CoreTexts
import de.trailscape.core.i18n.SessionTextKey

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
 * [TrainingSession.isEvent], [TrainingSession.textKey]); nur bei Altplaenen
 * ohne Textschluessel wird die lange Fahrt am Titel erkannt — alle
 * Planversionen davor nannten sie „Lange Tour".
 */

/** Ob [session] die lange Fahrt der Woche ist. */
fun isLongRideSession(session: TrainingSession): Boolean {
    if (session.isEvent) return false
    val key = session.textKey ?: return session.title.lowercase().startsWith("lange")
    return key == SessionTextKey.LONG_RIDE
}

/** Klartext-Titel, z. B. „Locker 45 km", „Lange Fahrt 80 km", „Hart 30 km". */
fun plainSessionTitle(session: TrainingSession, texts: CoreTexts): String {
    val km = session.targetKm
    val t = texts.training
    return when {
        session.isEvent -> t.plainTitleEvent(km)
        isLongRideSession(session) -> t.plainTitleLongRide(km)
        session.intensity == SessionIntensity.HART -> t.plainTitleHard(km)
        else -> t.plainTitleEasy(km)
    }
}

/**
 * Ein kurzer Satz, **wie** die Einheit gefahren wird — ohne Zonen und
 * Kuerzel. [goal] ergaenzt bei der langen Fahrt die Hoehenmeter-Erinnerung,
 * wenn das Ziel huegelig ist (ab 300 Hm).
 */
fun plainSessionHint(session: TrainingSession, goal: Goal? = null, texts: CoreTexts): String {
    val t = texts.training
    return when {
        session.isEvent -> t.plainHintEvent()
        isLongRideSession(session) -> t.plainHintLongRide(hilly = (goal?.ascentM ?: 0.0) >= 300)
        session.intensity == SessionIntensity.HART -> t.plainHintHard()
        session.intensity == SessionIntensity.LOCKER -> t.plainHintRecovery()
        else -> t.plainHintEndurance()
    }
}
