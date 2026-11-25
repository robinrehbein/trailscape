package de.trailscape.core.challenge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Format `*.trailscape-challenge`: Schreiben, Lesen, Pruefung, Namen, Strecken-Zuordnung. */
class ChallengeTest {
    /** 60 s mit 8 m/s, alle 5 s ein Wert (Dezimeter). */
    private fun curve(totalS: Double = 60.0, speed: Double = 8.0): ChallengeCurve {
        val step = 5
        val n = Math.ceil(totalS / step).toInt() + 1
        val dm = List(n) { i -> Math.round(minOf(i * step.toDouble(), totalS) * speed * 10).toInt() }
        return ChallengeCurve(step, totalS, dm)
    }

    private fun sample(
        route: ChallengeRoute = ChallengeRoute.Catalog("alpe-dhuez", 2),
        name: String = "Anna",
        date: String? = "2026-11-25",
        avatar: String? = null,
    ) = createChallenge(route, curve(), name, date, avatar)

    private fun ok(text: String): Challenge = assertIs<ChallengeParse.Ok>(parseChallenge(text)).challenge

    private fun rejected(text: String): ChallengeParse.Rejected = assertIs<ChallengeParse.Rejected>(parseChallenge(text))

    /** Eine Datei von Hand, so wie Version 1 sie festschreibt (Formatstabilitaet). */
    private val golden = """
        {"format":"trailscape-challenge","version":1,
         "route":{"kind":"catalog","id":"alpe-dhuez","version":2},
         "name":"Anna","date":"2026-11-25",
         "curve":{"stepS":5,"totalS":12.5,"dm":[0,400,800,1000]}}
    """.trimIndent()

    @Test
    fun `Schreiben und Lesen ergibt dieselbe Herausforderung`() {
        val c = sample(avatar = """{"v":1,"kit":{"jersey":"#1F6272"}}""")
        val back = ok(c.toJson())
        assertEquals(c, back)
        assertEquals(c.id, back.id)
        assertEquals(c.toJson(), back.toJson())
        assertEquals("""{"v":1,"kit":{"jersey":"#1F6272"}}""", back.avatarJson)
    }

    @Test
    fun `die Datei von Hand aus Version 1 wird gelesen`() {
        val c = ok(golden)
        assertEquals(ChallengeRoute.Catalog("alpe-dhuez", 2), c.route)
        assertEquals("Anna", c.name)
        assertEquals("2026-11-25", c.date)
        assertEquals(12.5, c.curve.totalS)
        assertEquals(listOf(0, 400, 800, 1000), c.curve.distDm)
        assertNull(c.avatarJson)
        assertEquals(100.0, c.curve.totalDistM)
        assertEquals(doubleArrayOf(0.0, 5.0, 10.0, 12.5).toList(), c.curve.timesS().toList())
    }

    @Test
    fun `die Ausgabe ist formatstabil`() {
        val c = ok(golden)
        assertEquals(
            """{"format":"trailscape-challenge","version":1,"route":{"kind":"catalog","id":"alpe-dhuez","version":2},""" +
                """"name":"Anna","date":"2026-11-25","curve":{"stepS":5,"totalS":12.5,"dm":[0,400,800,1000]}}""",
            c.toJson(),
        )
    }

    @Test
    fun `Teststrecke wird geschrieben und gelesen`() {
        assertEquals(ChallengeRoute.TestWorld, ok(sample(route = ChallengeRoute.TestWorld).toJson()).route)
    }

    @Test
    fun `die Datei enthaelt keine Puls- Leistungs- oder Ortsfelder`() {
        val json = sample(avatar = """{"v":1}""").toJson()
        for (key in listOf("hr", "power", "cadence", "lat", "lon", "time", "points", "device", "ele")) {
            assertFalse(json.contains("\"$key\""), key)
        }
        // Nur das Datum, keine Uhrzeit.
        assertFalse(Regex("\\d{2}:\\d{2}").containsMatchIn(json))
    }

    @Test
    fun `ohne Datum steht kein Datum in der Datei`() {
        val json = sample(date = null).toJson()
        assertFalse(json.contains("\"date\""))
        assertNull(ok(json).date)
    }

    @Test
    fun `unbekannte Felder werden ueberlesen`() {
        val text = golden
            .replace("\"name\"", "\"zukunft\":{\"a\":[1,2,3]},\"name\"")
            .replace("\"stepS\"", "\"note\":\"x\",\"stepS\"")
            .replace("\"kind\"", "\"extra\":true,\"kind\"")
        val c = ok(text)
        assertEquals("Anna", c.name)
        assertEquals(4, c.curve.distDm.size)
    }

    @Test
    fun `neuere Version ergibt eine Ablehnung mit der Versionsnummer`() {
        val r = rejected(golden.replace("\"version\":1,", "\"version\":7,"))
        assertEquals(ChallengeReject.TOO_NEW, r.reason)
        assertEquals(7, r.fileVersion)
        // Auch wenn der Rest der Datei in Version 7 anders aussieht.
        val r2 = rejected("""{"format":"trailscape-challenge","version":3,"strecke":42}""")
        assertEquals(ChallengeReject.TOO_NEW, r2.reason)
    }

    @Test
    fun `fremdes Format und fehlende Version werden abgelehnt`() {
        assertEquals(ChallengeReject.WRONG_FORMAT, rejected("""{"format":"gpx","version":1}""").reason)
        assertEquals(ChallengeReject.WRONG_FORMAT, rejected("""{"format":"trailscape-challenge"}""").reason)
        assertEquals(ChallengeReject.WRONG_FORMAT, rejected(golden.replace("\"version\":1,", "\"version\":0,")).reason)
        assertEquals(ChallengeReject.WRONG_FORMAT, rejected("{}").reason)
    }

    @Test
    fun `Muell wirft nie sondern wird abgelehnt`() {
        for (text in listOf("", "   ", "nicht json", "[1,2,3]", "null", "\"x\"", "{", "{\"format\":", "\u0000\u0001")) {
            assertEquals(ChallengeReject.NOT_JSON, rejected(text).reason, text)
        }
        assertEquals(ChallengeReject.NOT_JSON, rejected("[".repeat(100_000)).reason)
        assertEquals(ChallengeReject.NOT_JSON, rejected("{\"a\":".repeat(50_000)).reason)
        assertEquals(ChallengeReject.NOT_JSON, assertIs<ChallengeParse.Rejected>(parseChallenge(ByteArray(10) { 0xFF.toByte() })).reason)
    }

    @Test
    fun `zu grosse Datei wird abgelehnt`() {
        val big = ByteArray(CHALLENGE_MAX_BYTES + 1) { ' '.code.toByte() }
        assertEquals(ChallengeReject.TOO_LARGE, assertIs<ChallengeParse.Rejected>(parseChallenge(big)).reason)
        val justOk = golden.toByteArray()
        assertIs<ChallengeParse.Ok>(parseChallenge(justOk))
    }

    @Test
    fun `Byte-Order-Mark am Anfang schadet nicht`() {
        assertIs<ChallengeParse.Ok>(parseChallenge("﻿$golden"))
    }

    @Test
    fun `eigene Strecke wird abgelehnt - Datenschutz`() {
        val r = rejected(golden.replace("""{"kind":"catalog","id":"alpe-dhuez","version":2}""", """{"kind":"own","points":[[50.1,7.2]]}"""))
        assertEquals(ChallengeReject.ROUTE_OWN_UNSUPPORTED, r.reason)
        assertFalse(OWN_ROUTES_SHAREABLE)
    }

    @Test
    fun `kaputte Streckenangaben werden abgelehnt`() {
        val cat = """{"kind":"catalog","id":"alpe-dhuez","version":2}"""
        for (bad in listOf(
            """{"kind":"catalog","id":"Alpe Dhuez","version":2}""",
            """{"kind":"catalog","id":"../etc","version":2}""",
            """{"kind":"catalog","id":"alpe-dhuez","version":0}""",
            """{"kind":"catalog","id":"alpe-dhuez","version":"2"}""",
            """{"kind":"catalog","id":"alpe-dhuez"}""",
            """{"kind":"mond"}""",
            """{}""",
            "\"catalog\"",
        )) {
            assertEquals(ChallengeReject.ROUTE_INVALID, rejected(golden.replace(cat, bad)).reason, bad)
        }
        assertEquals(ChallengeReject.ROUTE_INVALID, rejected(golden.replace("\"route\":$cat,", "")).reason)
    }

    private fun withDm(dm: String, totalS: String = "12.5", step: String = "5") = golden.replace(
        """"curve":{"stepS":5,"totalS":12.5,"dm":[0,400,800,1000]}""",
        """"curve":{"stepS":$step,"totalS":$totalS,"dm":$dm}""",
    )

    @Test
    fun `nicht monotone oder verdrehte Kurven werden abgelehnt`() {
        for (text in listOf(
            withDm("[0,400,300,1000]"),
            withDm("[100,400,800,1000]"),
            withDm("[0,-1,800,1000]"),
            withDm("[0,400,800,\"x\"]"),
            withDm("[0,400,800,1000.5]"),
            withDm("[0]", totalS = "0"),
            withDm("[]"),
            withDm("[0,400,800,1000]", totalS = "30"),
            withDm("[0,400,800,1000]", totalS = "9"),
            withDm("[0,400,800,1000]", totalS = "12.5", step = "0"),
            withDm("[0,400,800,1000]", totalS = "12.5", step = "61"),
            withDm("[0,400,800,1000]", totalS = "NaN"),
            withDm("[0,400,800,1000]", totalS = "\"12.5\""),
            golden.replace("\"curve\"", "\"kurve\""),
        )) {
            assertEquals(ChallengeReject.CURVE_INVALID, rejected(text).reason, text)
        }
    }

    @Test
    fun `unfahrbare Kurven werden abgelehnt`() {
        // 5 s fuer 1 km = 200 m/s.
        assertEquals(ChallengeReject.CURVE_IMPLAUSIBLE, rejected(withDm("[0,10000,10001,10002]")).reason)
        // Unter 10 s Gesamtzeit.
        assertEquals(ChallengeReject.CURVE_IMPLAUSIBLE, rejected(withDm("[0,10]", totalS = "5")).reason)
        // Keine 50 m.
        assertEquals(ChallengeReject.CURVE_IMPLAUSIBLE, rejected(withDm("[0,10,20,30]")).reason)
        // Mehr als 24 h.
        val day = (0..1441).joinToString(",", "[", "]") { (it * 1000).toString() }
        assertEquals(ChallengeReject.CURVE_IMPLAUSIBLE, rejected(withDm(day, totalS = "86460", step = "60")).reason)
    }

    @Test
    fun `Stillstand in der Kurve ist erlaubt`() {
        val c = ok(withDm("[0,400,400,400,1000]", totalS = "17"))
        assertEquals(5, c.curve.distDm.size)
    }

    @Test
    fun `zu viele Abtastwerte werden abgelehnt`() {
        val dm = (0..CURVE_MAX_SAMPLES).joinToString(",", "[", "]") { (it * 10).toString() }
        assertEquals(
            ChallengeReject.CURVE_INVALID,
            rejected(withDm(dm, totalS = (CURVE_MAX_SAMPLES * 5.0).toString())).reason,
        )
    }

    // --- Namen, Datum, Avatar ---------------------------------------------

    @Test
    fun `Name wird bereinigt`() {
        assertEquals("Anna", sanitizeChallengeName("  Anna  "))
        assertEquals("Anna B", sanitizeChallengeName("Anna\n\t  B"))
        assertEquals("AnnaB", sanitizeChallengeName("Anna\u0000\u0007B"))
        // Richtungsumschalter und Nullbreitenzeichen entfallen.
        assertEquals("Anna", sanitizeChallengeName("‮Anna​⁦"))
        assertEquals("", sanitizeChallengeName(" \n\u0000 "))
        // Auf Codepunkte gekuerzt, kein halbes Emoji.
        val long = "🚴".repeat(40)
        val cut = sanitizeChallengeName(long)
        assertEquals(CHALLENGE_NAME_MAX_CHARS, cut.codePointCount(0, cut.length))
        assertEquals("Ännä Ö", sanitizeChallengeName("Ännä Ö"))
    }

    @Test
    fun `Name in der Datei wird beim Lesen bereinigt`() {
        val c = ok(golden.replace("\"Anna\"", "\"\\u202E  Anna\\u0007\\n\""))
        assertEquals("Anna", c.name)
        assertEquals("", ok(golden.replace("\"name\":\"Anna\",", "")).name)
    }

    @Test
    fun `unplausibles Datum wird verworfen die Fahrt bleibt`() {
        for (d in listOf("25.11.2026", "2026-13-40", "1999-01-01", "2026-11-25T10:00:00Z", "heute")) {
            assertNull(ok(golden.replace("2026-11-25", d)).date, d)
        }
        assertNull(createChallenge(ChallengeRoute.TestWorld, curve(), "x", "2026-02-30").date)
    }

    @Test
    fun `Avatar nur als kleines Objekt`() {
        val huge = """{"v":1,"x":"${"a".repeat(AVATAR_MAX_CHARS)}"}"""
        assertNull(createChallenge(ChallengeRoute.TestWorld, curve(), "x", null, huge).avatarJson)
        assertNull(ok(golden.replace("\"name\"", "\"avatar\":[1],\"name\"")).avatarJson)
        assertNull(ok(golden.replace("\"name\"", "\"avatar\":\"text\",\"name\"")).avatarJson)
        assertEquals("""{"v":2}""", ok(golden.replace("\"name\"", "\"avatar\":{\"v\":2},\"name\"")).avatarJson)
    }

    @Test
    fun `Kennung haengt vom Inhalt ab`() {
        assertEquals(sample().id, sample().id)
        assertNotEquals(sample().id, sample(name = "Ben").id)
        assertNotEquals(sample().id, sample(date = null).id)
        assertNotEquals(sample().id, sample(route = ChallengeRoute.Catalog("alpe-dhuez", 3)).id)
        assertEquals(16, sample().id.length)
    }

    // --- Zuordnung zur Strecke -----------------------------------------------

    @Test
    fun `nur bekannte Strecken und die Teststrecke sind teilbar`() {
        assertEquals(ChallengeRoute.Catalog("alpe-dhuez", 4), routeOfKey("catalog:alpe-dhuez", 4))
        assertEquals(ChallengeRoute.TestWorld, routeOfKey("testwelt", 1))
        assertNull(routeOfKey("ride:abc", 1))
        assertNull(routeOfKey("generated:1f", 1))
        assertNull(routeOfKey("catalog:Alpe Dhuez", 1))
        assertNull(routeOfKey("catalog:alpe-dhuez", 0))
        assertTrue(isShareableRouteKey("catalog:alpe-dhuez"))
        assertFalse(isShareableRouteKey("ride:abc"))
    }

    @Test
    fun `Streckenschluessel der Herausforderung passt zu dem der App`() {
        assertEquals("catalog:alpe-dhuez", ChallengeRoute.Catalog("alpe-dhuez", 2).key)
        assertEquals("testwelt", ChallengeRoute.TestWorld.key)
    }
}
