package de.trailscape.app.ui.map

import de.trailscape.core.Waypoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests der reinen Logik aus `MapTapPlanning.kt`: was ein Tipp bzw. ein langer
 * Druck auf die Karte bewirkt, und der Rueckgaengig-Stapel der Wegpunktliste.
 * Reine JVM-Tests ohne Compose.
 */
class MapTapPlanningTest {

    private fun tap(
        mode: MapMode = MapMode.ERKUNDEN,
        aufzeichnung: Boolean = false,
        navigation: Boolean = false,
        vorschlaegeOffen: Boolean = false,
        rundeUebernommen: Boolean = false,
        andereAufgabeOffen: Boolean = false,
    ) = mapTapAction(mode, aufzeichnung, navigation, vorschlaegeOffen, rundeUebernommen, andereAufgabeOffen)

    private fun longPress(
        mode: MapMode = MapMode.ERKUNDEN,
        aufzeichnung: Boolean = false,
        navigation: Boolean = false,
        vorschlaegeOffen: Boolean = false,
        rundeUebernommen: Boolean = false,
    ) = mapLongPressAction(mode, aufzeichnung, navigation, vorschlaegeOffen, rundeUebernommen)

    // -------------------------------------------------------------- Tipp

    @Test
    fun `im Ruhezustand markiert ein Tipp einen Punkt`() {
        assertEquals(MapPointAction.PUNKT_MARKIEREN, tap())
    }

    @Test
    fun `in der Planung haengt ein Tipp den naechsten Wegpunkt an`() {
        assertEquals(MapPointAction.WEGPUNKT_ANHAENGEN, tap(mode = MapMode.PLANEN))
    }

    @Test
    fun `waehrend Aufzeichnung und Navigation setzt ein Tipp nie einen Punkt`() {
        for (mode in MapMode.entries) {
            assertEquals(MapPointAction.KEINE, tap(mode = mode, aufzeichnung = true), "Aufzeichnung, $mode")
            assertEquals(MapPointAction.KEINE, tap(mode = mode, navigation = true), "Navigation, $mode")
        }
        assertEquals(MapPointAction.KEINE, tap(mode = MapMode.NAVIGIEREN))
    }

    @Test
    fun `ein Tipp raeumt keine offene Aufgabe weg`() {
        assertEquals(MapPointAction.KEINE, tap(andereAufgabeOffen = true))
        assertEquals(MapPointAction.KEINE, tap(vorschlaegeOffen = true))
    }

    @Test
    fun `eine offene Aufgabe des Erkundens haelt die Planung nicht auf`() {
        // In der Planung gibt es weder gewaehlte Tour noch Rundenwahl; ein
        // liegen gebliebenes Flag darf das Setzen nicht blockieren.
        assertEquals(MapPointAction.WEGPUNKT_ANHAENGEN, tap(mode = MapMode.PLANEN, andereAufgabeOffen = true))
    }

    @Test
    fun `ein Tipp ersetzt keine uebernommene Runde`() {
        assertEquals(MapPointAction.KEINE, tap(mode = MapMode.PLANEN, rundeUebernommen = true))
    }

    // ------------------------------------------------------ langer Druck

    @Test
    fun `der lange Druck setzt weiterhin Punkte`() {
        assertEquals(MapPointAction.PUNKT_MARKIEREN, longPress())
        assertEquals(MapPointAction.WEGPUNKT_ANHAENGEN, longPress(mode = MapMode.PLANEN))
    }

    @Test
    fun `der lange Druck fragt bei einer uebernommenen Runde nach`() {
        assertEquals(
            MapPointAction.RUNDE_ERSETZEN_FRAGEN,
            longPress(mode = MapMode.PLANEN, rundeUebernommen = true),
        )
    }

    @Test
    fun `der lange Druck tut waehrend Aufzeichnung, Navigation und offenen Vorschlaegen nichts`() {
        assertEquals(MapPointAction.KEINE, longPress(aufzeichnung = true))
        assertEquals(MapPointAction.KEINE, longPress(mode = MapMode.PLANEN, aufzeichnung = true))
        assertEquals(MapPointAction.KEINE, longPress(navigation = true))
        assertEquals(MapPointAction.KEINE, longPress(vorschlaegeOffen = true))
        // In der Planung zaehlen offene Vorschlaege nicht (wie vor dem Umbau).
        assertEquals(MapPointAction.WEGPUNKT_ANHAENGEN, longPress(mode = MapMode.PLANEN, vorschlaegeOffen = true))
    }

    // --------------------------------------------------------- Rueckgaengig

    private val a = Waypoint(48.0, 9.0)
    private val b = Waypoint(48.1, 9.1)
    private val c = Waypoint(48.2, 9.2, name = "Hayingen")

    @Test
    fun `ein leerer Stapel kann nichts zuruecknehmen`() {
        val stack = WaypointUndoStack()
        assertFalse(stack.canUndo)
        assertNull(stack.undo())
    }

    @Test
    fun `Rueckgaengig nimmt den zuletzt gesetzten Wegpunkt zurueck`() {
        var stack = WaypointUndoStack()
        var list = emptyList<Waypoint>()
        for (next in listOf(listOf(a), listOf(a, b), listOf(a, b, c))) {
            stack = stack.merke(vorher = list, nachher = next)
            list = next
        }
        assertTrue(stack.canUndo)

        val (nachErstem, rest1) = stack.undo()!!
        assertEquals(listOf(a, b), nachErstem)
        val (nachZweitem, rest2) = rest1.undo()!!
        assertEquals(listOf(a), nachZweitem)
        val (nachDrittem, rest3) = rest2.undo()!!
        assertEquals(emptyList(), nachDrittem)
        assertFalse(rest3.canUndo)
    }

    @Test
    fun `Rueckgaengig holt auch einen entfernten Wegpunkt zurueck`() {
        val stack = WaypointUndoStack().merke(vorher = listOf(a, b, c), nachher = listOf(a, c))
        assertEquals(listOf(a, b, c), stack.undo()!!.first)
    }

    @Test
    fun `eine Aenderung ohne Wirkung wird nicht gemerkt`() {
        val stack = WaypointUndoStack().merke(vorher = listOf(a), nachher = listOf(a))
        assertFalse(stack.canUndo)
    }

    @Test
    fun `der Stapel waechst nicht ueber seine Grenze`() {
        var stack = WaypointUndoStack()
        var list = emptyList<Waypoint>()
        repeat(WAYPOINT_UNDO_LIMIT + 5) { i ->
            val next = list + Waypoint(48.0 + i * 0.01, 9.0)
            stack = stack.merke(vorher = list, nachher = next)
            list = next
        }
        assertEquals(WAYPOINT_UNDO_LIMIT, stack.fruehere.size)
        // Der juengste Schritt bleibt der naechste, der zurueckgenommen wird.
        assertEquals(list.dropLast(1), stack.undo()!!.first)
    }
}
