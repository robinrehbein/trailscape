package de.trailscape.app.ui.components

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests der Spaltenentscheidung hinter [ActionTileRow] ([actionTileColumns]).
 * Die Breiten sind die aus dem Review: Inhaltsbreite des Tourdetails
 * (Bildschirm minus 2 × 16 dp Rand) und „Umbenennen" in `labelMedium`, rund
 * 74 dp bei 100 % Schrift.
 */
class ActionTileColumnsTest {

    private val umbenennen = 74.dp

    @Test
    fun `411 dp bei 100 Prozent passen vier nebeneinander`() {
        assertEquals(4, actionTileColumns(4, 379.dp, umbenennen))
    }

    @Test
    fun `360 dp brechen schon bei 100 Prozent auf zwei Spalten um`() {
        assertEquals(2, actionTileColumns(4, 328.dp, umbenennen))
    }

    @Test
    fun `411 dp bei 115 Prozent brechen auf zwei Spalten um`() {
        assertEquals(2, actionTileColumns(4, 379.dp, umbenennen * 1.15f))
    }

    @Test
    fun `nie drei je Reihe, sondern zwei`() {
        // Drei Kacheln passten hier, vier nicht — trotzdem zwei, nicht drei.
        assertEquals(2, actionTileColumns(4, 300.dp, 80.dp))
    }

    @Test
    fun `reicht nicht einmal die halbe Breite, steht jede Kachel allein`() {
        assertEquals(1, actionTileColumns(4, 200.dp, 120.dp))
    }

    @Test
    fun `zwei Aktionen bleiben nebeneinander, solange sie passen`() {
        assertEquals(2, actionTileColumns(2, 328.dp, umbenennen))
    }

    @Test
    fun `keine Aktion ergibt trotzdem eine gueltige Spaltenzahl`() {
        assertEquals(1, actionTileColumns(0, 328.dp, umbenennen))
    }
}
