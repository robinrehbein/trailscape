package de.trailscape.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JSON-Kompatibilitaetstests fuer die Portierung von `lib/models.dart`.
 *
 * Die JSON-Literale in diesen Tests sind Feld fuer Feld aus den
 * `toJson`/`fromJson`-Implementierungen in `lib/models.dart` abgeleitet
 * (inklusive der Faelle, in denen ein Feld komplett fehlt vs. als explizites
 * JSON-`null` geschrieben wird), damit echte, von der Flutter-App erzeugte
 * Tour-/Trainings-Dateien nach dem Umstieg auf die native App weiter lesbar
 * bleiben.
 */
class ModelsTest {
    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    // --- TrackPoint ---

    @Test
    fun `TrackPoint mit allen Feldern`() {
        val json = obj(
            """{"lat":52.5163,"lon":13.3777,"ele":45.2,"time":1700000000000,"hr":142}""",
        )

        val point = TrackPoint.fromJson(json)

        assertEquals(52.5163, point.lat, 1e-9)
        assertEquals(13.3777, point.lon, 1e-9)
        assertEquals(45.2, point.ele!!, 1e-9)
        assertEquals(1700000000000L, point.time)
        assertEquals(142, point.hr)

        assertEquals(point, TrackPoint.fromJson(point.toJson()))
    }

    @Test
    fun `TrackPoint ohne optionale Felder laesst deren Schluessel im JSON komplett weg`() {
        // Dart: `if (ele != null) 'ele': ele` usw. -> kein Schluessel, kein
        // explizites `null`, wenn das Feld nicht gesetzt ist.
        val json = obj("""{"lat":48.1,"lon":11.5}""")

        val point = TrackPoint.fromJson(json)
        assertNull(point.ele)
        assertNull(point.time)
        assertNull(point.hr)

        val roundTripped = point.toJson()
        assertFalse(roundTripped.containsKey("ele"))
        assertFalse(roundTripped.containsKey("time"))
        assertFalse(roundTripped.containsKey("hr"))
        assertEquals(point, TrackPoint.fromJson(roundTripped))
    }

    @Test
    fun `TrackPoint ohne lat wirft`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            TrackPoint.fromJson(obj("""{"lon":11.5}"""))
        }
    }

    @Test
    fun `TrackPoint toleriert unbekannte Felder`() {
        val point = TrackPoint.fromJson(obj("""{"lat":1.0,"lon":2.0,"unbekannt":"wert"}"""))
        assertEquals(1.0, point.lat, 1e-9)
    }

    // --- RideStats ---

    @Test
    fun `RideStats mit allen Feldern inklusive Herzfrequenz`() {
        val json = obj(
            """{"distanceKm":42.5,"durationS":3600,"movingTimeS":3500,"avgSpeedKmh":11.8,""" +
                """"ascentM":320.0,"descentM":310.0,"avgHrBpm":132,"maxHrBpm":178}""",
        )

        val stats = RideStats.fromJson(json)

        assertEquals(42.5, stats.distanceKm, 1e-9)
        assertEquals(3600, stats.durationS)
        assertEquals(3500, stats.movingTimeS)
        assertEquals(11.8, stats.avgSpeedKmh!!, 1e-9)
        assertEquals(320.0, stats.ascentM, 1e-9)
        assertEquals(310.0, stats.descentM, 1e-9)
        assertEquals(132, stats.avgHrBpm)
        assertEquals(178, stats.maxHrBpm)

        val roundTripped = stats.toJson()
        assertTrue(roundTripped.containsKey("avgHrBpm"))
        assertTrue(roundTripped.containsKey("maxHrBpm"))
        assertEquals(stats, RideStats.fromJson(roundTripped))
    }

    @Test
    fun `RideStats ohne Herzfrequenz laesst deren Schluessel weg, aber durationS-Trio bleibt als explizites null`() {
        // Dart schreibt 'durationS'/'movingTimeS'/'avgSpeedKmh' IMMER (auch als
        // JSON-`null`), aber 'avgHrBpm'/'maxHrBpm' nur `if (... != null)`.
        val json = obj(
            """{"distanceKm":5.0,"durationS":null,"movingTimeS":null,"avgSpeedKmh":null,""" +
                """"ascentM":0.0,"descentM":0.0}""",
        )

        val stats = RideStats.fromJson(json)
        assertNull(stats.durationS)
        assertNull(stats.movingTimeS)
        assertNull(stats.avgSpeedKmh)
        assertNull(stats.avgHrBpm)
        assertNull(stats.maxHrBpm)

        val roundTripped = stats.toJson()
        assertTrue(roundTripped.containsKey("durationS"))
        assertEquals(JsonNull, roundTripped["durationS"])
        assertTrue(roundTripped.containsKey("movingTimeS"))
        assertTrue(roundTripped.containsKey("avgSpeedKmh"))
        assertFalse(roundTripped.containsKey("avgHrBpm"))
        assertFalse(roundTripped.containsKey("maxHrBpm"))
        assertEquals(stats, RideStats.fromJson(roundTripped))
    }

    @Test
    fun `RideStats fromJson faellt bei fehlender distanceKm ascentM descentM auf 0 zurueck`() {
        // Dart: `(json['distanceKm'] as num?)?.toDouble() ?? 0` — lenient, im
        // Gegensatz zu z. B. Goal.distanceKm.
        val stats = RideStats.fromJson(obj("""{}"""))

        assertEquals(0.0, stats.distanceKm, 1e-9)
        assertEquals(0.0, stats.ascentM, 1e-9)
        assertEquals(0.0, stats.descentM, 1e-9)
        assertNull(stats.durationS)
        assertNull(stats.avgHrBpm)
    }

    // --- Ride ---

    @Test
    fun `Ride Roundtrip wie in storage_test dart`() {
        // Exakt dieselben Werte wie `_ride()` in test/storage_test.dart.
        val json = obj(
            """
            {
              "id": "abc",
              "name": "Tour abc",
              "createdAt": 1700000000000,
              "points": [
                {"lat":47.1,"lon":11.1,"ele":500.0,"time":1700000000000},
                {"lat":47.2,"lon":11.2,"ele":520.0,"time":1700000060000}
              ],
              "stats": {"distanceKm":1.2,"durationS":60,"movingTimeS":60,"avgSpeedKmh":12.0,"ascentM":20.0,"descentM":0.0}
            }
            """.trimIndent(),
        )

        val ride = Ride.fromJson(json)

        assertEquals("abc", ride.id)
        assertEquals("Tour abc", ride.name)
        assertEquals(1700000000000L, ride.createdAt)
        assertEquals(2, ride.points.size)
        assertEquals(47.1, ride.points[0].lat, 1e-9)
        assertEquals(1.2, ride.stats.distanceKm, 1e-9)

        assertEquals(ride, Ride.fromJson(ride.toJson()))
    }

    @Test
    fun `Ride ohne stats-Feld faellt auf leere RideStats zurueck`() {
        val json = obj("""{"id":"x","name":"n","createdAt":1,"points":[]}""")

        val ride = Ride.fromJson(json)

        assertEquals(RideStats.empty, ride.stats)
    }

    @Test
    fun `Ride mit stats gleich null faellt ebenfalls auf leere RideStats zurueck`() {
        // Dart: `json['stats'] is Map<String, dynamic>` ist false fuer `null`.
        val json = obj("""{"id":"x","name":"n","createdAt":1,"points":[],"stats":null}""")

        val ride = Ride.fromJson(json)

        assertEquals(RideStats.empty, ride.stats)
    }

    @Test
    fun `Ride ohne updatedAt-Schluessel faellt auf createdAt zurueck (alte Datei)`() {
        // Alte Tour-Dateien (Flutter-App, Web-App, Bestandsserver) kennen den
        // Schluessel nicht — fehlend heisst: nie bearbeitet, so alt wie die
        // Aufzeichnung.
        val json = obj("""{"id":"x","name":"n","createdAt":1700000000000,"points":[]}""")

        val ride = Ride.fromJson(json)

        assertEquals(1700000000000L, ride.updatedAt)
    }

    @Test
    fun `Ride schreibt updatedAt immer und liest es im Roundtrip zurueck`() {
        val ride = Ride(
            id = "x",
            name = "n",
            createdAt = 1700000000000L,
            stats = RideStats.empty,
            points = emptyList(),
            updatedAt = 1700000123456L,
        )

        val json = ride.toJson()
        assertTrue(json.containsKey("updatedAt"))

        val roundTripped = Ride.fromJson(json)
        assertEquals(1700000123456L, roundTripped.updatedAt)
        assertEquals(ride, roundTripped)
    }

    @Test
    fun `Ride mit explizitem updatedAt gleich null faellt auf createdAt zurueck`() {
        val json = obj("""{"id":"x","name":"n","createdAt":5,"points":[],"updatedAt":null}""")

        assertEquals(5L, Ride.fromJson(json).updatedAt)
    }

    @Test
    fun `Ride ohne id wirft wie Darts null as String`() {
        val json = obj("""{"name":"n","createdAt":1,"points":[],"stats":{}}""")

        assertFailsWith<MissingOrInvalidFieldException> { Ride.fromJson(json) }
    }

    @Test
    fun `Ride mit kaputtem JSON-Objekt wie wrong-shape json aus storage_test wirft`() {
        // Entspricht `jsonEncode({'foo': 'bar'})` aus storage_test.dart: kein
        // gueltiges Ride-Objekt, storage.dart faengt das generisch ab.
        val json = obj("""{"foo":"bar"}""")

        assertFailsWith<MissingOrInvalidFieldException> { Ride.fromJson(json) }
    }

    // --- Goal ---

    @Test
    fun `Goal mit allen Feldern`() {
        val json = obj("""{"name":"Alpencross","distanceKm":180.0,"ascentM":4200.0,"date":1735689600000}""")

        val goal = Goal.fromJson(json)

        assertEquals("Alpencross", goal.name)
        assertEquals(180.0, goal.distanceKm, 1e-9)
        assertEquals(4200.0, goal.ascentM!!, 1e-9)
        assertEquals(1735689600000L, goal.date)

        assertEquals(goal, Goal.fromJson(goal.toJson()))
    }

    @Test
    fun `Goal ohne ascentM behaelt den Schluessel als explizites null`() {
        // Dart: `'ascentM': ascentM` — der Schluessel steht immer im JSON,
        // anders als bei TrackPoint.
        val json = obj("""{"name":"Feierabendrunde","distanceKm":25.0,"ascentM":null,"date":1700000000000}""")

        val goal = Goal.fromJson(json)
        assertNull(goal.ascentM)

        val roundTripped = goal.toJson()
        assertTrue(roundTripped.containsKey("ascentM"))
        assertEquals(JsonNull, roundTripped["ascentM"])
        assertEquals(goal, Goal.fromJson(roundTripped))
    }

    @Test
    fun `Goal ohne distanceKm wirft weil Dart hier as num ohne Fragezeichen nutzt`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            Goal.fromJson(obj("""{"name":"x","date":1}"""))
        }
    }

    @Test
    fun `Goal mit targetTimeMin round-tripped`() {
        val json = obj(
            """{"name":"Alpencross","distanceKm":180.0,"ascentM":4200.0,"targetTimeMin":390,"date":1735689600000}""",
        )

        val goal = Goal.fromJson(json)

        assertEquals(390, goal.targetTimeMin!!)
        assertEquals(goal, Goal.fromJson(goal.toJson()))
    }

    @Test
    fun `Goal ohne Zielzeit behaelt den Schluessel als explizites null`() {
        // Wie ascentM: der Schluessel steht immer im JSON. Alte Dateien ohne
        // den Schluessel (und neue ohne Zeitziel) lesen sich beide als null.
        val json = obj(
            """{"name":"Feierabendrunde","distanceKm":25.0,"ascentM":null,"targetTimeMin":null,"date":1700000000000}""",
        )

        val goal = Goal.fromJson(json)

        assertNull(goal.targetTimeMin)
        val roundTripped = goal.toJson()
        assertTrue(roundTripped.containsKey("targetTimeMin"))
        assertEquals(JsonNull, roundTripped["targetTimeMin"])
        assertEquals(goal, Goal.fromJson(roundTripped))
    }

    @Test
    fun `Goal aus einer alten Datei ohne targetTimeMin-Schluessel liest null`() {
        val json = obj("""{"name":"Alpencross","distanceKm":180.0,"ascentM":4200.0,"date":1735689600000}""")

        assertNull(Goal.fromJson(json).targetTimeMin)
    }

    // --- TrainingPlan / TrainingWeek / TrainingSession ---

    @Test
    fun `TrainingPlan verschachtelter Roundtrip`() {
        val json = obj(
            """
            {
              "createdAt": 1700000000000,
              "goal": {"name":"Alpencross","distanceKm":180.0,"ascentM":4200.0,"date":1735689600000},
              "level": "fortgeschritten",
              "weeks": [
                {
                  "index": 0,
                  "start": 1700000000000,
                  "end": 1700604800000,
                  "kind": "aufbau",
                  "targetKm": 120,
                  "sessions": [
                    {"day":"Montag","title":"Grundlage","description":"Lockere Ausfahrt","targetKm":40}
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        val plan = TrainingPlan.fromJson(json)

        assertEquals(1700000000000L, plan.createdAt)
        assertEquals("Alpencross", plan.goal.name)
        assertEquals(FitnessLevel.FORTGESCHRITTEN, plan.level)
        assertEquals(1, plan.weeks.size)
        assertEquals(WeekKind.AUFBAU, plan.weeks[0].kind)
        assertEquals(120, plan.weeks[0].targetKm)
        assertEquals(1, plan.weeks[0].sessions.size)
        assertEquals("Montag", plan.weeks[0].sessions[0].day)

        assertEquals(plan, TrainingPlan.fromJson(plan.toJson()))
    }

    @Test
    fun `TrainingWeek kind wird als Dart-Enum-Name serialisiert nicht als Kotlin-Name`() {
        val week = TrainingWeek(
            index = 1,
            start = 0,
            end = 1,
            kind = WeekKind.ZIELWOCHE,
            targetKm = 50,
            sessions = emptyList(),
        )

        val json = week.toJson()
        assertEquals("zielwoche", (json["kind"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `unbekannter WeekKind-Name wirft wie Darts values byName`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            WeekKind.fromJsonName("nicht-existent")
        }
    }

    @Test
    fun `unbekannter FitnessLevel-Name wirft wie Darts values byName`() {
        assertFailsWith<MissingOrInvalidFieldException> {
            FitnessLevel.fromJsonName("nicht-existent")
        }
    }

    @Test
    fun `FitnessLevel jsonName entspricht dem Dart-Enum-Namen`() {
        assertEquals("einsteiger", FitnessLevel.EINSTEIGER.jsonName)
        assertEquals("fortgeschritten", FitnessLevel.FORTGESCHRITTEN.jsonName)
        assertEquals("ambitioniert", FitnessLevel.AMBITIONIERT.jsonName)
    }

    @Test
    fun `levelLabels und weekKindLabels entsprechen den Dart-Konstanten`() {
        assertEquals("Einsteiger", levelLabels[FitnessLevel.EINSTEIGER])
        assertEquals("Fortgeschritten", levelLabels[FitnessLevel.FORTGESCHRITTEN])
        assertEquals("Ambitioniert", levelLabels[FitnessLevel.AMBITIONIERT])

        assertEquals("Aufbau", weekKindLabels[WeekKind.AUFBAU])
        assertEquals("Erholung", weekKindLabels[WeekKind.ERHOLUNG])
        assertEquals("Taper", weekKindLabels[WeekKind.TAPER])
        assertEquals("Zielwoche", weekKindLabels[WeekKind.ZIELWOCHE])
    }
}
