package de.trailscape.core.challenge

import de.trailscape.core.indoor.GhostCurve
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json

/**
 * # Herausforderung: die geteilte Fahrt
 *
 * Eine Datei `*.trailscape-challenge` traegt **eine** Indoor-Fahrt als
 * Mitfahrer zu einem anderen Geraet („fahr meine Zeit nach"). Kein Konto, kein
 * Server: Die Datei geht von Person zu Person. Format und Datenschutz stehen in
 * `docs/herausforderung.md`.
 *
 * ## Was drin ist — und was nicht
 *  * Kennung der Strecke: eine **bekannte Strecke** (Katalogkennung samt
 *    Version) oder die eingebaute Teststrecke. Eigene Strecken (Aussenfahrten)
 *    werden **nicht** geteilt ([OWN_ROUTES_SHAREABLE] = `false`): Die Linie
 *    einer eigenen Fahrt verraet Wohnort und Gewohnheiten. Das Format reserviert
 *    dafuer die Art `own`; ein Leser lehnt sie ab ([ChallengeReject.ROUTE_OWN_UNSUPPORTED]).
 *  * Der Zeit-/Distanzverlauf als kompakte Kurve ([ChallengeCurve]).
 *  * Ein kurzer Anzeigename, bereinigt ([sanitizeChallengeName]).
 *  * Optional das Datum der Fahrt (`JJJJ-MM-TT`, nur der Tag, abschaltbar).
 *  * Optional ein **undurchsichtiges** Avatar-Objekt: Dieses Modul liest nur,
 *    dass es ein JSON-Objekt unter [AVATAR_MAX_CHARS] ist, und gibt es
 *    unveraendert weiter.
 *  * **Nie**: Puls, Leistung, Trittfrequenz, Orte, Uhrzeiten, Geraetekennungen.
 *
 * ## Lesen ist streng, aber vorwaertskompatibel
 * Unbekannte Felder werden ueberlesen; eine **neuere Version** ergibt eine
 * verstaendliche Ablehnung ([ChallengeReject.TOO_NEW]), nie eine Ausnahme.
 * [parseChallenge] wirft nie.
 */

const val CHALLENGE_FORMAT: String = "trailscape-challenge"

/** Version, die dieser Code schreibt und liest. */
const val CHALLENGE_VERSION: Int = 1

const val CHALLENGE_FILE_EXTENSION: String = "trailscape-challenge"

/** MIME-Typ beim Teilen (eigener Typ, damit Trailscape die Datei erkennt). */
const val CHALLENGE_MIME_TYPE: String = "application/vnd.trailscape.challenge"

/**
 * Bewusste Entscheidung (Datenschutz): eigene Aussenstrecken sind **nicht**
 * teilbar. Ein Schalter, damit die Entscheidung an genau einer Stelle steht;
 * `true` braeuchte zusaetzlich die ausdrueckliche Zustimmung des Absenders und
 * die Streckengeometrie im Format (Version 2).
 */
const val OWN_ROUTES_SHAREABLE: Boolean = false

/** Groesste Datei, die gelesen wird (Bytes). Eine 24-h-Fahrt ist rund 100 kB. */
const val CHALLENGE_MAX_BYTES: Int = 256 * 1024

const val CHALLENGE_NAME_MAX_CHARS: Int = 24
const val AVATAR_MAX_CHARS: Int = 2048

const val CURVE_MAX_SAMPLES: Int = 20_000
const val CURVE_MAX_TOTAL_S: Double = 86_400.0
const val CURVE_MIN_TOTAL_S: Double = 10.0
const val CURVE_MIN_DIST_M: Double = 50.0
const val CURVE_MAX_STEP_S: Int = 60
const val CURVE_DEFAULT_STEP_S: Int = 5

/** Schneller als das (m/s) ist keine Indoor-Fahrt: Datei gilt als unplausibel. */
const val CURVE_MAX_SPEED_MPS: Double = 40.0

/** Tiefste Schachtelung, die der Leser zulaesst (das Format braucht 4). */
private const val MAX_JSON_DEPTH = 16

private val CATALOG_ID = Regex("[a-z0-9][a-z0-9-]{0,63}")
private val DATE = Regex("\\d{4}-\\d{2}-\\d{2}")

/** Auf welcher Strecke die Fahrt lief. */
sealed interface ChallengeRoute {
    /** Schluessel wie `IndoorRouteOption.key` der App („catalog:<id>", „testwelt"). */
    val key: String

    /** Eine bekannte Strecke; [version] ist die Welt-Version des Katalogs. */
    data class Catalog(val id: String, val version: Int) : ChallengeRoute {
        override val key: String get() = "catalog:$id"
    }

    /** Die eingebaute Teststrecke. */
    data object TestWorld : ChallengeRoute {
        override val key: String = "testwelt"
    }
}

/**
 * Der Verlauf der Fahrt: Laufmeter zu festen Zeitpunkten.
 *
 * Gleichmaessig abgetastet, damit die Datei klein bleibt und die Zeit
 * automatisch streng steigt: Abtastwert `i` gehoert zur Zeit `i · stepS`, der
 * **letzte** zur Gesamtzeit [totalS] (≤ `(n−1) · stepS`). Laufmeter in
 * Dezimetern, monoton steigend, beginnend bei 0. Pausen sind schon
 * herausgekuerzt (die Fahrzeit zaehlt).
 */
class ChallengeCurve(val stepS: Int, val totalS: Double, val distDm: List<Int>) {
    init {
        require(stepS in 1..CURVE_MAX_STEP_S)
        require(distDm.size in 2..CURVE_MAX_SAMPLES)
        require(totalS.isFinite() && totalS in CURVE_MIN_TOTAL_S..CURVE_MAX_TOTAL_S)
        require(totalS > (distDm.size - 2) * stepS && totalS <= (distDm.size - 1) * stepS + 1e-9)
        require(distDm[0] == 0)
        for (i in 1 until distDm.size) require(distDm[i] >= distDm[i - 1])
    }

    val totalDistM: Double get() = distDm.last() / 10.0

    /** Zeitpunkte der Abtastwerte in s. */
    fun timesS(): DoubleArray = DoubleArray(distDm.size) { if (it == distDm.size - 1) totalS else it.toDouble() * stepS }

    /** Laufmeter der Abtastwerte. */
    fun distsM(): DoubleArray = DoubleArray(distDm.size) { distDm[it] / 10.0 }

    internal fun toGhostCurve(): GhostCurve = GhostCurve(timesS(), distsM())

    override fun equals(other: Any?): Boolean =
        other is ChallengeCurve && stepS == other.stepS && totalS == other.totalS && distDm == other.distDm

    override fun hashCode(): Int = (stepS * 31 + totalS.hashCode()) * 31 + distDm.hashCode()

    companion object {
        /**
         * Tastet [curve] gleichmaessig ab; `null`, wenn sie fuer eine
         * Herausforderung zu kurz/unbrauchbar ist.
         */
        internal fun from(curve: GhostCurve, stepS: Int = CURVE_DEFAULT_STEP_S): ChallengeCurve? {
            val total = Math.round(curve.timesS.last() * 1000.0) / 1000.0
            if (!total.isFinite() || total < CURVE_MIN_TOTAL_S || total > CURVE_MAX_TOTAL_S) return null
            val samples = ArrayList<Int>()
            var k = 0
            while (k.toDouble() * stepS < total && samples.size < CURVE_MAX_SAMPLES - 1) {
                samples += Math.round(curve.at(k.toDouble() * stepS).first * 10.0).toInt()
                k++
            }
            samples += Math.round(curve.at(total).first * 10.0).toInt()
            // Rundung und Abtastung duerfen die Reihenfolge nicht brechen.
            for (i in 1 until samples.size) if (samples[i] < samples[i - 1]) samples[i] = samples[i - 1]
            if (samples[0] != 0 || samples.last() < CURVE_MIN_DIST_M * 10) return null
            return runCatching { ChallengeCurve(stepS, total, samples) }.getOrNull()
        }
    }
}

/**
 * Eine Herausforderung.
 *
 * @property name Anzeigename, bereits bereinigt; kann leer sein (die App zeigt dann einen Ersatz).
 * @property date Tag der Fahrt `JJJJ-MM-TT` oder `null` (abgeschaltet oder unlesbar).
 * @property avatarJson das Avatar-Objekt als JSON-Text, unveraendert weitergereicht; `null` ohne.
 */
class Challenge(
    val route: ChallengeRoute,
    val name: String,
    val date: String?,
    val curve: ChallengeCurve,
    val avatarJson: String? = null,
) {
    /** Inhaltsgleiche Dateien haben dieselbe Kennung (Doppelte beim Import erkennen). */
    val id: String by lazy {
        val sha = MessageDigest.getInstance("SHA-256").digest(toJson().toByteArray(Charsets.UTF_8))
        sha.take(8).joinToString("") { "%02x".format(it) }
    }

    /** Die Datei, deterministisch (gleiche Eingabe, gleiche Bytes). */
    fun toJson(): String = buildJsonObject {
        put("format", CHALLENGE_FORMAT)
        put("version", CHALLENGE_VERSION)
        put(
            "route",
            buildJsonObject {
                when (route) {
                    is ChallengeRoute.Catalog -> {
                        put("kind", "catalog")
                        put("id", route.id)
                        put("version", route.version)
                    }
                    ChallengeRoute.TestWorld -> put("kind", "testworld")
                }
            },
        )
        put("name", name)
        date?.let { put("date", it) }
        put(
            "curve",
            buildJsonObject {
                put("stepS", curve.stepS)
                put("totalS", curve.totalS)
                put("dm", buildJsonArray { curve.distDm.forEach { add(JsonPrimitive(it)) } })
            },
        )
        avatarJson?.let { a ->
            runCatching { Json.parseToJsonElement(a) }.getOrNull()?.takeIf { it is JsonObject }?.let { put("avatar", it) }
        }
    }.toString()

    override fun equals(other: Any?): Boolean = other is Challenge && toJson() == other.toJson()
    override fun hashCode(): Int = toJson().hashCode()
}

/** Warum eine Datei nicht angenommen wird. */
enum class ChallengeReject {
    /** Groesser als [CHALLENGE_MAX_BYTES]. */
    TOO_LARGE,

    /** Kein (brauchbares) JSON. */
    NOT_JSON,

    /** Kein `trailscape-challenge`-Dokument. */
    WRONG_FORMAT,

    /** Von einer neueren App geschrieben ([ChallengeParse.Rejected.fileVersion]). */
    TOO_NEW,

    /** Strecke fehlt oder ist unlesbar. */
    ROUTE_INVALID,

    /** Eigene Strecke: wird nicht unterstuetzt (Datenschutz). */
    ROUTE_OWN_UNSUPPORTED,

    /** Kurve fehlt, ist nicht monoton oder sonst kaputt. */
    CURVE_INVALID,

    /** Kurve ist formal in Ordnung, aber nicht fahrbar (Tempo, Laenge). */
    CURVE_IMPLAUSIBLE,
}

/** Ergebnis von [parseChallenge]. */
sealed interface ChallengeParse {
    data class Ok(val challenge: Challenge) : ChallengeParse

    data class Rejected(val reason: ChallengeReject, val fileVersion: Int? = null) : ChallengeParse
}

/** Liest die Bytes einer Datei; wirft nie. */
fun parseChallenge(bytes: ByteArray): ChallengeParse {
    if (bytes.size > CHALLENGE_MAX_BYTES) return ChallengeParse.Rejected(ChallengeReject.TOO_LARGE)
    return parseChallenge(String(bytes, Charsets.UTF_8))
}

/** Liest den Text einer Datei; wirft nie. */
fun parseChallenge(text: String): ChallengeParse = try {
    parseChallengeUnsafe(text)
} catch (_: Exception) {
    ChallengeParse.Rejected(ChallengeReject.NOT_JSON)
} catch (_: StackOverflowError) {
    ChallengeParse.Rejected(ChallengeReject.NOT_JSON)
}

private fun parseChallengeUnsafe(raw: String): ChallengeParse {
    val text = raw.removePrefix("﻿")
    if (text.length > CHALLENGE_MAX_BYTES) return ChallengeParse.Rejected(ChallengeReject.TOO_LARGE)
    if (jsonDepthExceeds(text, MAX_JSON_DEPTH)) return ChallengeParse.Rejected(ChallengeReject.NOT_JSON)
    val root = Json.parseToJsonElement(text) as? JsonObject ?: return ChallengeParse.Rejected(ChallengeReject.NOT_JSON)

    if (root.str("format") != CHALLENGE_FORMAT) return ChallengeParse.Rejected(ChallengeReject.WRONG_FORMAT)
    val version = root.int("version") ?: return ChallengeParse.Rejected(ChallengeReject.WRONG_FORMAT)
    if (version < 1) return ChallengeParse.Rejected(ChallengeReject.WRONG_FORMAT)
    if (version > CHALLENGE_VERSION) return ChallengeParse.Rejected(ChallengeReject.TOO_NEW, fileVersion = version)

    val routeObj = root["route"] as? JsonObject ?: return ChallengeParse.Rejected(ChallengeReject.ROUTE_INVALID)
    val route = when (routeObj.str("kind")) {
        "catalog" -> {
            val id = routeObj.str("id")
            val v = routeObj.int("version")
            if (id == null || !CATALOG_ID.matches(id) || v == null || v !in 1..9999) {
                return ChallengeParse.Rejected(ChallengeReject.ROUTE_INVALID)
            }
            ChallengeRoute.Catalog(id, v)
        }
        "testworld" -> ChallengeRoute.TestWorld
        "own" -> return ChallengeParse.Rejected(ChallengeReject.ROUTE_OWN_UNSUPPORTED)
        else -> return ChallengeParse.Rejected(ChallengeReject.ROUTE_INVALID)
    }

    val curveObj = root["curve"] as? JsonObject ?: return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    val stepS = curveObj.int("stepS")
    val totalS = (curveObj["totalS"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toDoubleOrNull()
    val dmArr = curveObj["dm"] as? JsonArray
    if (stepS == null || stepS !in 1..CURVE_MAX_STEP_S || totalS == null || !totalS.isFinite() || dmArr == null) {
        return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    }
    if (dmArr.size < 2 || dmArr.size > CURVE_MAX_SAMPLES) return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    val dm = ArrayList<Int>(dmArr.size)
    for (e in dmArr) {
        val p = e as? JsonPrimitive
        val v = p?.takeIf { !it.isString }?.content?.toIntOrNull()
        if (v == null || v < 0) return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
        dm += v
    }
    val n = dm.size
    if (dm[0] != 0) return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    for (i in 1 until n) if (dm[i] < dm[i - 1]) return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    if (!(totalS > (n - 2) * stepS && totalS <= (n - 1) * stepS + 1e-9)) {
        return ChallengeParse.Rejected(ChallengeReject.CURVE_INVALID)
    }
    if (totalS < CURVE_MIN_TOTAL_S || totalS > CURVE_MAX_TOTAL_S || dm[n - 1] < CURVE_MIN_DIST_M * 10) {
        return ChallengeParse.Rejected(ChallengeReject.CURVE_IMPLAUSIBLE)
    }
    for (i in 1 until n) {
        val dt = if (i == n - 1) totalS - (n - 2) * stepS else stepS.toDouble()
        if ((dm[i] - dm[i - 1]) / 10.0 / dt > CURVE_MAX_SPEED_MPS) {
            return ChallengeParse.Rejected(ChallengeReject.CURVE_IMPLAUSIBLE)
        }
    }
    val curve = ChallengeCurve(stepS, totalS, dm)

    val name = sanitizeChallengeName(root.str("name") ?: "")
    val date = root.str("date")?.takeIf { validDate(it) }
    // Unbekannte oder zu grosse Avatar-Felder werden still verworfen: Die
    // Fahrt zaehlt, das Aussehen ist Beiwerk.
    val avatar = (root["avatar"] as? JsonObject)?.toString()?.takeIf { it.length <= AVATAR_MAX_CHARS }
    return ChallengeParse.Ok(Challenge(route, name, date, curve, avatar))
}

/** Baut eine zu teilende Herausforderung: Name bereinigt, Datum geprueft. */
fun createChallenge(
    route: ChallengeRoute,
    curve: ChallengeCurve,
    name: String,
    date: String?,
    avatarJson: String? = null,
): Challenge = Challenge(
    route = route,
    name = sanitizeChallengeName(name),
    date = date?.takeIf { validDate(it) },
    curve = curve,
    avatarJson = avatarJson?.takeIf { it.length <= AVATAR_MAX_CHARS },
)

/**
 * Ob eine Strecke geteilt werden darf: bekannte Strecken und die Teststrecke ja;
 * alles andere (eigene Aussenstrecken, Planungen, erzeugte Strecken) nein
 * ([OWN_ROUTES_SHAREABLE]).
 */
fun isShareableRouteKey(key: String): Boolean = routeOfKey(key, 1) != null

/** Die Strecken-Kennung zum Schluessel einer Strecke; `null` = nicht teilbar. */
fun routeOfKey(key: String, catalogVersion: Int): ChallengeRoute? = when {
    key == ChallengeRoute.TestWorld.key -> ChallengeRoute.TestWorld
    key.startsWith("catalog:") -> {
        val id = key.removePrefix("catalog:")
        if (CATALOG_ID.matches(id) && catalogVersion in 1..9999) ChallengeRoute.Catalog(id, catalogVersion) else null
    }
    else -> null
}

/**
 * Kurzer, sicherer Anzeigename: ohne Steuer- und Formatzeichen (auch keine
 * Richtungsumschalter), Leerraum zusammengezogen, hoechstens
 * [CHALLENGE_NAME_MAX_CHARS] Zeichen (ganze Codepunkte). Leer ist erlaubt.
 */
fun sanitizeChallengeName(raw: String): String {
    val sb = StringBuilder()
    var lastSpace = true
    var count = 0
    var i = 0
    while (i < raw.length && count < CHALLENGE_NAME_MAX_CHARS) {
        val cp = raw.codePointAt(i)
        i += Character.charCount(cp)
        val type = Character.getType(cp)
        val isSpace = Character.isWhitespace(cp) || Character.isSpaceChar(cp)
        val bad = when (type.toByte()) {
            Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED -> true
            else -> false
        }
        if (isSpace) {
            if (!lastSpace) {
                sb.append(' ')
                lastSpace = true
                count++
            }
        } else if (!bad) {
            sb.appendCodePoint(cp)
            lastSpace = false
            count++
        }
    }
    return sb.toString().trim()
}

private fun validDate(s: String): Boolean {
    if (!DATE.matches(s)) return false
    return try {
        val d = java.time.LocalDate.parse(s)
        d.year in 2000..2200
    } catch (_: Exception) {
        false
    }
}

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.content?.toIntOrNull()

/** Grober Schachtelungstest ohne Parser, damit „[[[[…" nicht den Stapel sprengt. */
private fun jsonDepthExceeds(text: String, limit: Int): Boolean {
    var depth = 0
    var inString = false
    var escaped = false
    for (c in text) {
        if (inString) {
            if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') inString = false
        } else when (c) {
            '"' -> inString = true
            '[', '{' -> if (++depth > limit) return true
            ']', '}' -> depth--
        }
    }
    return false
}
