package de.trailscape.app.ui.map

import de.trailscape.core.Waypoint

/**
 * # Tipp auf die Karte und „Rückgängig" der Planung — die reine Logik
 *
 * Seit dem Umbau der Bedienstruktur (docs/ux/seitenkarte.md, Befund 8 und
 * Entscheidungsfrage 4) setzt schon ein **einfacher Tipp** auf die Karte den
 * naechsten Punkt; vorher tat das nur der lange Druck, und ein Tipp tat
 * nichts. Fehltipper faengt der Knopf „Rückgängig" im Planungsblatt ab.
 *
 * Beides steht hier als reine Funktionen ohne Compose, damit sich die Regeln
 * als JVM-Tests pruefen lassen (`MapTapPlanningTest`). `MapScreen.kt` fragt
 * sie nur ab und fuehrt aus.
 */

/** Was eine Geste auf der Karte (Tipp oder langer Druck) bewirkt. */
internal enum class MapPointAction {
    /** Nichts — die Karte ist gerade nicht zum Punktesetzen da. */
    KEINE,

    /** Ruhezustand: Punkt markieren und die Ortskarte „Markierter Punkt" oeffnen. */
    PUNKT_MARKIEREN,

    /** Planung: den Punkt als naechsten Wegpunkt anhaengen. */
    WEGPUNKT_ANHAENGEN,

    /**
     * Planung mit uebernommener Runde: erst nachfragen, ob mit eigenen Punkten
     * weitergeplant werden soll (die Runde ginge sonst verloren).
     */
    RUNDE_ERSETZEN_FRAGEN,
}

/**
 * Entscheidet, was ein **einfacher Tipp** auf die Karte bewirkt.
 *
 * Waehrend Aufzeichnung und Navigation nichts (am Lenker darf ein Tipp nichts
 * anlegen), ebenso solange Vorschlaege einer Runde offen sind. Der Tipp ist
 * bewusst vorsichtiger als der lange Druck ([mapLongPressAction]):
 *
 *  * Beim Erkunden setzt er nur dann einen Punkt, wenn die Karte frei ist
 *    oder schon eine Ortskarte zeigt (die dann auf den neuen Punkt springt).
 *    Ist eine andere Aufgabe offen ([andereAufgabeOffen]: gewaehlte Tour,
 *    Rundenwahl, Verlaufskarte, laufende Suche, hochgewischtes „Wohin?"),
 *    raeumt ein beilaeufiger Tipp sie nicht weg.
 *  * Eine uebernommene Runde ersetzt er nie — dafuer bleibt der lange Druck
 *    mit seiner Rueckfrage.
 */
internal fun mapTapAction(
    mode: MapMode,
    aufzeichnung: Boolean,
    navigation: Boolean,
    vorschlaegeOffen: Boolean,
    rundeUebernommen: Boolean,
    andereAufgabeOffen: Boolean,
): MapPointAction = when {
    aufzeichnung || navigation || mode == MapMode.NAVIGIEREN -> MapPointAction.KEINE
    mode == MapMode.PLANEN ->
        if (rundeUebernommen) MapPointAction.KEINE else MapPointAction.WEGPUNKT_ANHAENGEN
    vorschlaegeOffen || andereAufgabeOffen -> MapPointAction.KEINE
    else -> MapPointAction.PUNKT_MARKIEREN
}

/**
 * Entscheidet, was ein **langer Druck** auf die Karte bewirkt — dieselbe
 * Regel wie vor dem Umbau: Er setzt in jedem ruhigen Zustand einen Punkt,
 * auch ueber eine gewaehlte Tour hinweg, und bietet bei einer uebernommenen
 * Runde an, mit eigenen Punkten weiterzuplanen.
 */
internal fun mapLongPressAction(
    mode: MapMode,
    aufzeichnung: Boolean,
    navigation: Boolean,
    vorschlaegeOffen: Boolean,
    rundeUebernommen: Boolean,
): MapPointAction = when {
    aufzeichnung || navigation -> MapPointAction.KEINE
    mode == MapMode.PLANEN ->
        if (rundeUebernommen) MapPointAction.RUNDE_ERSETZEN_FRAGEN else MapPointAction.WEGPUNKT_ANHAENGEN
    vorschlaegeOffen -> MapPointAction.KEINE
    else -> MapPointAction.PUNKT_MARKIEREN
}

/** So viele Schritte merkt sich „Rückgängig" hoechstens. */
internal const val WAYPOINT_UNDO_LIMIT = 30

/**
 * Der Rueckgaengig-Stapel der Wegpunktliste: je Aenderung der Stand **davor**.
 *
 * Unveraenderlich — jede Operation liefert einen neuen Stapel, damit er als
 * gewoehnlicher Compose-Zustand gehalten werden kann. Gemerkt wird jede
 * einzelne Aenderung an der Liste (Tipp, langer Druck, Ort als Wegpunkt,
 * „Mein Standort als Start", ✕ einer Zeile); „Rückgängig" nimmt genau die
 * letzte davon zurueck. Wird die Liste als Ganzes ersetzt (Planung beendet,
 * alles entfernt, Runde uebernommen), beginnt der Stapel leer — dafuer gibt
 * es die Rueckhol-Snackbar.
 */
internal data class WaypointUndoStack(val fruehere: List<List<Waypoint>> = emptyList()) {

    /** Ob es etwas zurueckzunehmen gibt (der Knopf ist sonst deaktiviert). */
    val canUndo: Boolean get() = fruehere.isNotEmpty()

    /**
     * Merkt [vorher] als Stand vor einer Aenderung, die zu [nachher] fuehrt.
     * Aendert sich nichts, bleibt der Stapel, wie er ist.
     */
    fun merke(vorher: List<Waypoint>, nachher: List<Waypoint>): WaypointUndoStack =
        if (vorher == nachher) this else WaypointUndoStack((fruehere + listOf(vorher)).takeLast(WAYPOINT_UNDO_LIMIT))

    /**
     * Nimmt die letzte Aenderung zurueck: die wiederherzustellende Liste und
     * der gekuerzte Stapel — oder `null`, wenn es nichts zurueckzunehmen gibt.
     */
    fun undo(): Pair<List<Waypoint>, WaypointUndoStack>? {
        val letzter = fruehere.lastOrNull() ?: return null
        return letzter to WaypointUndoStack(fruehere.dropLast(1))
    }
}
