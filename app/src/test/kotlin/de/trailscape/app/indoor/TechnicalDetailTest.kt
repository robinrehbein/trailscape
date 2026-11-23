package de.trailscape.app.indoor

import de.trailscape.core.indoor.world.WorldBuildFailed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Die kopierbare „Technische Einzelheit" der Fehlerkarten ([technicalDetailOf]). */
class TechnicalDetailTest {

    private fun thrownHere(e: Throwable): Throwable = try {
        throw e
    } catch (t: Throwable) {
        t
    }

    @Test
    fun innersteUrsacheMitMeldungUndEigenerStackZeile() {
        val root = thrownHere(IllegalArgumentException("Index 70000 ausserhalb"))
        val wrapped = IndoorPartException(
            problemOf(IndoorPart.WORLD, root),
            WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, "Index 70000 ausserhalb", root),
        )
        val detail = technicalDetailOf(wrapped)
        assertTrue(detail.startsWith("IllegalArgumentException: Index 70000 ausserhalb @ de.trailscape.app.indoor.TechnicalDetailTest"), detail)
        assertTrue(Regex(":\\d+$").containsMatchIn(detail), "mit Zeilennummer: $detail")
    }

    @Test
    fun ursacheOhneMeldungBekommtDieAeussereMeldung() {
        val detail = technicalDetailOf(
            WorldBuildFailed(WorldBuildFailed.Reason.INTERNAL, "Budget ueberschritten", thrownHere(NullPointerException())),
        )
        assertTrue(detail.startsWith("NullPointerException ("), detail)
        assertTrue("Budget ueberschritten) @ de.trailscape." in detail, detail)
    }

    @Test
    fun errorsUndFremdeKlassenBehaltenDenVollenNamen() {
        val oom = technicalDetailOf(OutOfMemoryError("Failed to allocate"))
        assertTrue(oom.startsWith("OutOfMemoryError: Failed to allocate"), oom)
        val io = technicalDetailOf(java.io.IOException("timeout"))
        assertTrue(io.startsWith("java.io.IOException: timeout"), io)
    }

    @Test
    fun wirdGekuerztUndEinzeilig() {
        val detail = technicalDetailOf(IllegalStateException("x".repeat(1000) + "\nzweite Zeile"))
        assertEquals(TECHNICAL_DETAIL_MAX_CHARS, detail.length)
        assertTrue(detail.endsWith("…"))
        assertTrue('\n' !in technicalDetailOf(IllegalStateException("a\nb")))
    }

    @Test
    fun zyklischeUrsachenkettenHaltenAn() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)
        assertTrue(technicalDetailOf(b).isNotEmpty())
    }
}
