package de.trailscape.app.ui

/**
 * # Die Navigationsstruktur als reine Logik
 *
 * Welche Tabs es gibt, wohin ein Wunsch ([AppTab], [IndoorDestination])
 * fuehrt, wann die Leiste zuruecktritt und welche Segmente „Indoor" und
 * „Verlauf" zeigen — alles ohne Compose und ohne `NavController`, damit es
 * sich als schlichter JVM-Test pruefen laesst (`AppNavigationTest`). Die
 * Huelle (`TrailscapeApp.kt`) fuehrt nur noch aus, was hier entschieden wird.
 *
 * Grundlage ist die Seitenkarte (`docs/ux/seitenkarte.md`); was davon
 * umgesetzt ist und wo kommende Funktionen eingehaengt werden, steht in
 * `docs/ux/umbau.md`.
 */
object AppRoutes {
    const val HOME = "heute"
    const val MAP = "karte"

    /** Der Tab „Indoor" (Strecken, Plan, Start der Indoor-Fahrt). */
    const val INDOOR = "indoor"

    /** Der Tab „Verlauf" (Touren, Fortschritt). Die Route heisst aus der Zeit davor „touren". */
    const val RIDES = "touren"

    /** Die Einstellungen hinterm Zahnrad — gepusht, kein Tab. */
    const val MORE = "mehr"

    /** Gepushter Indoor-Ablauf mit vorgewaehlter Strecke ([IndoorDestination.FLOW]). */
    const val INDOOR_FLOW = "indoor/ablauf"

    /** „Einstellungen → Indoor" ([IndoorDestination.SETTINGS]). */
    const val INDOOR_SETTINGS = "indoor/einstellungen"
    const val INDOOR_WORLDS = "indoor/welten"
    const val INDOOR_DIAGNOSTICS = "indoor/diagnose"

    /**
     * Die Garage (Fahrer und Rad anpassen, `docs/indoor/garage.md`) — gepusht
     * vom Symbol in der Kopfzeile des Indoor-Tabs, Sicht „Strecken". Kein
     * [IndoorDestination]: Es gibt genau diesen einen Einstieg, keinen Sprung
     * von aussen.
     */
    const val INDOOR_GARAGE = "indoor/garage"
}

/** Ein Platz in der Navigationsleiste. */
data class ShellTab(val tab: AppTab, val route: String)

/**
 * Die vier Tabs in der Reihenfolge der Leiste: Heute · Karte · Indoor ·
 * Verlauf. Vier ist das Maximum, das die schwebende Kapsel einzeilig
 * beschriftet traegt; ein fuenfter Platz (etwa fuer Erfolge) ist deshalb
 * ausdruecklich nicht vorgesehen.
 */
val SHELL_TABS: List<ShellTab> = listOf(
    ShellTab(AppTab.HOME, AppRoutes.HOME),
    ShellTab(AppTab.MAP, AppRoutes.MAP),
    ShellTab(AppTab.INDOOR, AppRoutes.INDOOR),
    ShellTab(AppTab.RIDES, AppRoutes.RIDES),
)

/** Was die Huelle mit einem Navigationswunsch tut. */
sealed interface NavCommand {
    /** Tab-Wechsel: ersetzt den sichtbaren Tab, der Backstack waechst nicht. */
    data class SwitchTab(val route: String) : NavCommand

    /** Zweite Ebene ueber dem sichtbaren Tab, mit Zurueck-Weg dorthin. */
    data class Push(val route: String) : NavCommand
}

/** Loest einen Tab-Wunsch auf: die vier Tabs wechseln, [AppTab.MORE] wird gepusht. */
fun navCommandFor(tab: AppTab): NavCommand {
    val shellTab = SHELL_TABS.firstOrNull { it.tab == tab }
    return if (shellTab != null) NavCommand.SwitchTab(shellTab.route) else NavCommand.Push(AppRoutes.MORE)
}

/** Loest einen Indoor-Wunsch auf: immer eine gepushte Seite. */
fun navCommandFor(destination: IndoorDestination): NavCommand.Push = NavCommand.Push(
    when (destination) {
        IndoorDestination.FLOW -> AppRoutes.INDOOR_FLOW
        IndoorDestination.SETTINGS -> AppRoutes.INDOOR_SETTINGS
        IndoorDestination.WORLDS -> AppRoutes.INDOOR_WORLDS
        IndoorDestination.DIAGNOSTICS -> AppRoutes.INDOOR_DIAGNOSTICS
    },
)

/** Ob [route] eine gepushte zweite Ebene ist (Shared-Axis-Uebergang, keine Leiste). */
fun isPushedRoute(route: String?): Boolean =
    route != null && SHELL_TABS.none { it.route == route }

/**
 * Ob Navigationsleiste und Fahren-Knopf zuruecktreten.
 *
 *  * Auf jeder gepushten Seite (Einstellungen, Indoor-Ablauf, Diagnose …):
 *    Eine Leiste ohne markiertes Ziel behauptete, man sei nirgends.
 *  * Auf der Karte, solange sie eine Aufgabe zeigt ([mapTaskActive]).
 *  * Im Verlauf, solange das Tourdetail offen ist ([rideDetailOpen]).
 *  * Im Indoor-Tab, solange er eine Aufgabe zeigt, die den ganzen Bildschirm
 *    braucht ([indoorTaskActive]) — vor allem waehrend der Fahrt.
 *
 * Die drei Zustaende gelten nur auf ihrem eigenen Tab: Ein auf der Karte
 * liegen gebliebener Wert blendet die Leiste nicht auf „Heute" aus.
 */
fun navigationBarHidden(
    route: String?,
    mapTaskActive: Boolean,
    rideDetailOpen: Boolean,
    indoorTaskActive: Boolean,
): Boolean = when {
    isPushedRoute(route) -> true
    route == AppRoutes.MAP -> mapTaskActive
    route == AppRoutes.RIDES -> rideDetailOpen
    route == AppRoutes.INDOOR -> indoorTaskActive
    else -> false
}

/**
 * Die Segmente des Tabs „Verlauf": Touren | Fortschritt [| Erfolge].
 *
 * @param achievementsVisible ob es die Erfolge gibt **und** sie eingeschaltet
 *   sind („Erfolge anzeigen"). Sonst fehlt das Segment ganz — kein leerer
 *   Platzhalter.
 */
fun historySegments(achievementsVisible: Boolean): List<HistorySegment> = buildList {
    add(HistorySegment.TOURS)
    add(HistorySegment.PROGRESS)
    if (achievementsVisible) add(HistorySegment.ACHIEVEMENTS)
}

/**
 * Die Segmente des Tabs „Indoor": Strecken | Plan [| Herausforderungen] [| Raum]
 * — oder, mit [together], Strecken | Plan | Gemeinsam.
 *
 * @param challengesAvailable ob es die Herausforderungen gibt.
 * @param roomAvailable ob es den Raum fuer Gruppenfahrten gibt.
 * @param together fasst Herausforderungen und Raum zu **einem** Segment
 *   „Gemeinsam" mit zwei Abschnitten zusammen, sobald es beide gibt. Gibt es
 *   nur eines von beiden, behaelt es sein eigenes Segment.
 */
fun indoorSegments(
    challengesAvailable: Boolean,
    roomAvailable: Boolean,
    together: Boolean = false,
): List<IndoorSegment> = buildList {
    add(IndoorSegment.ROUTES)
    add(IndoorSegment.PLAN)
    if (together && challengesAvailable && roomAvailable) {
        add(IndoorSegment.TOGETHER)
    } else {
        if (challengesAvailable) add(IndoorSegment.CHALLENGES)
        if (roomAvailable) add(IndoorSegment.ROOM)
    }
}

/**
 * Welche der festgelegten Plaetze im Indoor-Tab eine Funktion haben
 * (`docs/ux/umbau.md`, „Was jetzt wo haengt"): Herausforderungen und der Raum
 * fuer Gruppenfahrten sind beide da.
 */
const val INDOOR_CHALLENGES_AVAILABLE: Boolean = true
const val INDOOR_ROOM_AVAILABLE: Boolean = true

/**
 * Die Platzfrage (`docs/ux/umbau.md`): Vier Segmente passen nicht in die Zeile.
 * Die Segmente sind gleich breit; auf 360 dp bleiben bei vieren je rund 66 dp
 * Text, „Herausforderungen" braucht bei 14 sp rund 125 dp — es war schon bei
 * dreien abgeschnitten. Deshalb teilen sich Herausforderungen und Raum das
 * Segment „Gemeinsam" (der dort festgelegte Ausweg).
 */
const val INDOOR_TOGETHER_MERGED: Boolean = true

/** Die Segmente des Indoor-Tabs, wie die App sie heute zeigt: Strecken | Plan | Gemeinsam. */
fun shippedIndoorSegments(): List<IndoorSegment> = indoorSegments(
    challengesAvailable = INDOOR_CHALLENGES_AVAILABLE,
    roomAvailable = INDOOR_ROOM_AVAILABLE,
    together = INDOOR_TOGETHER_MERGED,
)

/**
 * Wohin ein Wunsch nach [wanted] fuehrt: Gibt es „Gemeinsam", landen
 * [IndoorSegment.ROOM] und [IndoorSegment.CHALLENGES] dort (ihre Abschnitte);
 * alles andere bleibt, was es ist.
 */
fun indoorSegmentTarget(wanted: IndoorSegment?, available: List<IndoorSegment>): IndoorSegment? = when {
    wanted == null || wanted in available -> wanted
    (wanted == IndoorSegment.ROOM || wanted == IndoorSegment.CHALLENGES) && IndoorSegment.TOGETHER in available ->
        IndoorSegment.TOGETHER
    else -> wanted
}

/**
 * Das im Indoor-Tab gezeigte Segment. Laeuft im Tab eine Aufgabe (Koppeln,
 * Vorbereiten, Fahrt, Auswertung — [taskActive]), bleibt „Strecken" stehen:
 * Der Ablauf lebt in dieser Sicht, und ein Sprungwunsch von aussen (ein
 * geoeffnetes Plan-Rezept, eine gespeicherte Herausforderung, ein
 * Einladungslink) darf eine Fahrt nicht aus der Komposition nehmen.
 */
fun shownIndoorSegment(wanted: IndoorSegment?, available: List<IndoorSegment>, taskActive: Boolean): IndoorSegment =
    if (taskActive) IndoorSegment.ROUTES else resolveSegment(indoorSegmentTarget(wanted, available), available)

/**
 * Ob [segment] im Indoor-Ablauf lebt (derselbe `IndoorViewModel` wie die
 * Startseite): „Strecken", „Herausforderungen", „Raum" und „Gemeinsam" — der
 * Raum setzt wie eine Herausforderung Strecke und Fahrt im selben ViewModel.
 * „Plan" ist eine eigene Sicht daneben.
 */
fun indoorSegmentUsesFlow(segment: IndoorSegment): Boolean = when (segment) {
    IndoorSegment.ROUTES, IndoorSegment.CHALLENGES, IndoorSegment.ROOM, IndoorSegment.TOGETHER -> true
    IndoorSegment.PLAN -> false
}

/**
 * Ob die Kopfzeile der Indoor-Startseite das Garage-Symbol traegt: nur im Tab
 * (Sicht „Strecken"). Der gepushte Ablauf mit vorgewaehlter Strecke fuehrt auf
 * eine Fahrt zu und bekommt keinen Abzweig; ein Einstieg genuegt.
 */
fun garageEntryVisible(indoorFlowInTab: Boolean): Boolean = indoorFlowInTab

/**
 * Das gezeigte Segment, wenn [wanted] (gemerkt oder angefordert) nicht — oder
 * nicht mehr — in [available] steht: dann das erste. So faellt der Verlauf
 * auf „Touren" zurueck, wenn die Erfolge ausgeschaltet werden, waehrend ihr
 * Segment offen ist.
 */
fun <T> resolveSegment(wanted: T?, available: List<T>): T =
    if (wanted != null && wanted in available) wanted else available.first()
