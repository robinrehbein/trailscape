package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Das optionale Feld `heute` im Wochenstand ([WochenGlance.heute]): alte und
 * neue Telegramme muessen sich in beide Richtungen vertragen.
 */
class WearGlanceHeuteTest {

    private val mittwoch = LocalDate.of(2026, 11, 18)
    private val vorschlag = HeuteVorschlag(mittwoch.toEpochDay(), drinnen = true, minuten = 60, gewaehlt = true)

    private fun stand(heute: HeuteVorschlag? = vorschlag) =
        WochenGlance.von(5, true, 42.0, 60, mittwoch, standMs = 1_000L, vorschlag = heute)

    @Test
    fun `Hin und zurueck mit Vorschlag`() {
        val s = stand()
        assertEquals(s, dekodiereWochenGlance(kodiereWochenGlance(s)))
        assertEquals(vorschlag, heuteFuerTag(s, mittwoch))
    }

    @Test
    fun `ohne Vorschlag ist das Telegramm byte-gleich zum alten`() {
        val ohne = kodiereWochenGlance(stand(heute = null)).toString(Charsets.UTF_8)
        assertFalse(ohne.contains("heute"))
        val alt = WochenGlance(5, true, 42.0, 60, WochenGlance.von(0, false, 0.0, null, mittwoch, 0).montagEpochTag, 1_000L)
        assertEquals(kodiereWochenGlance(alt).toString(Charsets.UTF_8), ohne)
    }

    @Test
    fun `alter Dekoder liest das neue Telegramm und uebergeht den Vorschlag`() {
        val alt = alterDekoder(kodiereWochenGlance(stand()))
        assertNotNull(alt)
        assertEquals(stand().copy(heute = null), alt)
    }

    @Test
    fun `neuer Dekoder liest ein altes Telegramm ohne Vorschlag`() {
        val altesTelegramm = """{"v":1,"serie":5,"erledigt":true,"km":42.0,"ziel":60,"montag":${stand().montagEpochTag},"stand":1000}"""
        val s = dekodiereWochenGlance(altesTelegramm.toByteArray())
        assertNotNull(s)
        assertNull(s.heute)
    }

    @Test
    fun `kaputter Vorschlag kostet nur den Vorschlag`() {
        val montag = stand().montagEpochTag
        listOf(
            """"heute":"drinnen"""",
            """"heute":{"tag":"x","ort":"drinnen"}""",
            """"heute":{"tag":${mittwoch.toEpochDay()},"ort":"irgendwo"}""",
            """"heute":{"tag":${mittwoch.toEpochDay()},"ort":"drinnen","min":0}""",
            """"heute":{"tag":${mittwoch.toEpochDay()},"ort":"drinnen","min":9999}""",
            """"heute":{"tag":${mittwoch.toEpochDay()},"ort":"drinnen","min":"60"}""",
        ).forEach { feld ->
            val text = """{"v":1,"serie":5,"erledigt":true,"km":42.0,"montag":$montag,"stand":1,$feld}"""
            val s = dekodiereWochenGlance(text.toByteArray())
            assertNotNull(s, feld)
            assertNull(s.heute, feld)
            assertEquals(5, s.serieWochen)
        }
    }

    @Test
    fun `Flags fehlen oder sind falsch getypt - dann false`() {
        val v = parseHeuteVorschlag("""{"tag":${mittwoch.toEpochDay()},"ort":"draussen","erledigt":"ja"}""")
        assertEquals(HeuteVorschlag(mittwoch.toEpochDay(), drinnen = false), v)
        assertTrue(parseHeuteVorschlag("""{"tag":${mittwoch.toEpochDay()},"ort":"draussen","erledigt":true}""")!!.erledigt)
        assertNull(parseHeuteVorschlag("kaputt"))
        assertNull(parseHeuteVorschlag(null))
    }

    @Test
    fun `Vorschlag gilt nur am eigenen Tag`() {
        assertNull(heuteFuerTag(stand(), mittwoch.plusDays(1)))
        assertNull(heuteFuerTag(stand(heute = null), mittwoch))
        assertNull(heuteFuerTag(null, mittwoch))
    }

    @Test
    fun `von verwirft einen ungueltigen Vorschlag`() {
        assertNull(stand(heute = vorschlag.copy(minuten = 0)).heute)
    }

    @Test
    fun `Vorschlag gehoert zum Inhalt`() {
        assertFalse(stand().gleicherInhalt(stand(heute = null)))
        assertTrue(stand().gleicherInhalt(stand().copy(standMs = 9L)))
    }

    /**
     * Eingefrorene Kopie von `dekodiereWochenGlance` vor dem Feld `heute` —
     * so dekodiert eine Uhr mit der alten App-Version.
     */
    private fun alterDekoder(bytes: ByteArray): WochenGlance? = runCatching {
        val json = Json.parseToJsonElement(bytes.toString(StandardCharsets.UTF_8)) as? JsonObject ?: return null
        fun zahl(key: String): Double? = (json[key] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
        if (zahl("v")?.toInt() != 1) return null
        val serie = zahl("serie")?.toInt() ?: return null
        val km = zahl("km") ?: return null
        val montag = zahl("montag")?.toLong() ?: return null
        val stand = zahl("stand")?.toLong() ?: return null
        val erledigt = (json["erledigt"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull()
            ?: return null
        val ziel = if ("ziel" in json) (zahl("ziel")?.toInt() ?: return null) else null
        WochenGlance(serie, erledigt, km, ziel, montag, stand)
    }.getOrNull()
}
