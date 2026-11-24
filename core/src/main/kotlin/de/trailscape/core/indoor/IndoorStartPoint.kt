package de.trailscape.core.indoor

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * # Startpunkt waehlen und Fortsetzen
 *
 * Eine Indoor-Fahrt muss nicht am Anfang der Strecke beginnen: Vor dem ersten
 * Tritt und in der Pause laesst sich der Fahrer an eine andere Stelle der
 * Strecke setzen, und wer eine Fahrt mittendrin beendet, kann beim naechsten
 * Mal dort weiterfahren (docs/indoor/app.md, Abschnitt „Startpunkt und
 * Fortsetzen").
 *
 * Die Vertragsdatei `IndoorSession.kt` bleibt unberuehrt: Das Versetzen ist
 * eine eigene Schnittstelle [MovableIndoorSession], die die Sitzung aus
 * `createIndoorSession` zusaetzlich erfuellt. Wer eine andere Sitzung hat
 * (Attrappe im Test), bekommt von [moveStartTo] einfach `false`.
 *
 * ## Was „gefahren" heisst
 *  * [IndoorSessionState.distM] bleibt der Laufmeter auf der Strecke (ueber
 *    Runden fortlaufend) — Darstellung, Kamera, Kacheln und Steigung folgen
 *    ihm und stimmen damit ab dem ersten Bild.
 *  * [IndoorTotals.distanceM] und die Hoehenmeter zaehlen nur, was wirklich
 *    gefahren wurde; der uebersprungene Teil zaehlt nie, auch nicht ein
 *    Versatz in der Pause.
 *  * Runden: Auf einer Runde zaehlt `lap` weiter die angefangenen Runden ab
 *    dem Laufmeter — wer in der Mitte startet, ist in Runde 1, bis er die
 *    Ziellinie quert. Versetzt wird immer innerhalb der laufenden Runde.
 */
interface MovableIndoorSession : IndoorSession {
    /** Ob der Fahrer gerade versetzt werden darf: vor dem ersten Tritt ([IndoorPhase.READY]) und in der Pause ([IndoorPhase.PAUSED]). */
    val canMove: Boolean

    /**
     * Setzt den Fahrer an die Stelle [routeDistM] (Meter innerhalb der Runde,
     * wird mit [IndoorStartPoints.clamp] begrenzt). Tempo 0, der Geist beginnt
     * dort neu, die naechste Vorgabe an den Trainer gilt fuer die neue Stelle.
     *
     * @return `true`, wenn versetzt wurde; `false` ausserhalb von READY und PAUSED.
     */
    fun moveTo(routeDistM: Double): Boolean
}

/** Versetzt den Fahrer, wenn die Sitzung das kann (siehe [MovableIndoorSession.moveTo]). */
fun IndoorSession.moveStartTo(routeDistM: Double): Boolean = (this as? MovableIndoorSession)?.moveTo(routeDistM) ?: false

/** Eine neue Sitzung wie `createIndoorSession`, der Fahrer steht schon an [startRouteDistM]. */
fun createIndoorSessionAt(config: IndoorSessionConfig, startRouteDistM: Double): IndoorSession =
    IndoorSessionImpl(config).also { it.moveTo(startRouteDistM) }

/**
 * Die gemerkte Stelle einer beendeten Fahrt auf einer Strecke.
 *
 * @property routeDistM Meter innerhalb der Runde, an denen die Fahrt endete.
 * @property routeLengthM Laenge der Strecke damals — ist die Strecke heute
 *   deutlich anders lang (Welt neu gebaut, andere Planung), passt die Stelle
 *   nicht mehr ([IndoorStartPoints.offer]).
 * @property savedAtEpochMs Wanduhrzeit des Merkens.
 */
data class IndoorResumePoint(val routeDistM: Double, val routeLengthM: Double, val savedAtEpochMs: Long) {
    fun toJson(): String = buildJsonObject {
        put("v", 1)
        put("routeDistM", routeDistM)
        put("routeLengthM", routeLengthM)
        put("savedAt", savedAtEpochMs)
    }.toString()

    companion object {
        /** Liest [toJson]; `null` bei kaputtem oder unbekanntem Inhalt (dann gibt es eben kein Angebot). */
        fun fromJson(text: String?): IndoorResumePoint? {
            if (text.isNullOrBlank()) return null
            val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            val d = obj["routeDistM"]?.jsonPrimitive?.doubleOrNull ?: return null
            val len = obj["routeLengthM"]?.jsonPrimitive?.doubleOrNull ?: return null
            val at = obj["savedAt"]?.jsonPrimitive?.longOrNull ?: 0L
            if (!d.isFinite() || !len.isFinite() || d < 0 || len <= 0) return null
            return IndoorResumePoint(d, len, at)
        }
    }
}

/** Regeln fuer Startpunkt und Fortsetzen — reine Funktionen, ohne Speicher. */
object IndoorStartPoints {
    /** Naeher am Start als so viele Meter lohnt kein „Weiter bei …" — dann gilt „Von vorn". */
    const val MIN_RESUME_M: Double = 200.0

    /**
     * Auf einer Strecke ohne Rundschluss bleibt bis zum Ziel mindestens so viel
     * Strecke: Ein Start direkt im Ziel waere sofort zu Ende.
     */
    const val GOAL_RESERVE_M: Double = 100.0

    /** Raster der Startstelle: so fein, wie es ein Daumen auf dem Profil trifft. */
    const val SNAP_M: Double = 10.0

    /** Schritt der Feinknoepfe (−/+) neben dem Profil. */
    const val NUDGE_M: Double = 100.0

    /** So viel darf die Strecke heute laenger oder kuerzer sein als beim Merken (Anteil). */
    const val LENGTH_TOLERANCE: Double = 0.05

    /** Spaeteste Startstelle. */
    fun maxStartM(lengthM: Double, isLoop: Boolean): Double {
        if (!lengthM.isFinite() || lengthM <= 0) return 0.0
        return if (isLoop) max(0.0, lengthM - SNAP_M) else max(0.0, lengthM - GOAL_RESERVE_M)
    }

    /** Begrenzt [routeDistM] auf 0 bis [maxStartM] und rundet auf [SNAP_M]; `NaN` → 0. */
    fun clamp(routeDistM: Double, lengthM: Double, isLoop: Boolean): Double {
        val top = maxStartM(lengthM, isLoop)
        if (!routeDistM.isFinite()) return 0.0
        val snapped = (routeDistM / SNAP_M).roundToLong() * SNAP_M
        return snapped.coerceIn(0.0, top)
    }

    /** Die Startstelle um [deltaM] verschoben (Feinknoepfe), begrenzt wie [clamp]. */
    fun nudge(routeDistM: Double, deltaM: Double, lengthM: Double, isLoop: Boolean): Double =
        clamp(routeDistM + deltaM, lengthM, isLoop)

    /**
     * Was nach dem Beenden einer Fahrt zu merken ist; `null` heisst: die
     * gemerkte Stelle loeschen.
     *
     *  * Strecke ohne Rundschluss, Ziel erreicht → loeschen.
     *  * Stelle naeher als [MIN_RESUME_M] am Start → loeschen (das waere „Von vorn").
     *  * Strecke ohne Rundschluss, Stelle hinter [maxStartM] → loeschen (praktisch im Ziel).
     *  * Runde, Stelle naeher als [MIN_RESUME_M] an der Ziellinie → loeschen
     *    (die naechste Runde beginnt ohnehin dort).
     *  * sonst die Stelle innerhalb der Runde.
     *
     * Auf einer Runde gibt es kein endgueltiges Ziel: Wer sie einmal ganz
     * gefahren hat und in der zweiten Runde aufhoert, setzt beim naechsten Mal
     * an dieser Stelle fort.
     */
    fun afterStop(
        routeDistM: Double,
        lengthM: Double,
        isLoop: Boolean,
        nowEpochMs: Long,
    ): IndoorResumePoint? {
        if (!routeDistM.isFinite() || !lengthM.isFinite() || lengthM <= 0) return null
        val d = if (isLoop) routeDistM - floor(routeDistM / lengthM) * lengthM else routeDistM.coerceIn(0.0, lengthM)
        if (!isLoop && d >= lengthM - 1e-6) return null
        if (d < MIN_RESUME_M) return null
        if (!isLoop && d > maxStartM(lengthM, false)) return null
        if (isLoop && lengthM - d < MIN_RESUME_M) return null
        return IndoorResumePoint(routeDistM = d, routeLengthM = lengthM, savedAtEpochMs = nowEpochMs)
    }

    /** [afterStop] aus dem letzten Zustand der Sitzung. */
    fun afterStop(state: IndoorSessionState, route: IndoorRoute, nowEpochMs: Long): IndoorResumePoint? =
        afterStop(state.routeDistM, route.lengthM, route.isLoop, nowEpochMs)

    /**
     * Die Stelle, an der „Weiter" heute ansetzt; `null` = kein Angebot (nichts
     * gemerkt, oder die Strecke ist heute mehr als [LENGTH_TOLERANCE] anders
     * lang). Bei kleinen Laengenunterschieden (Streckenband der Welt gegen die
     * urspruengliche Tour) wird die Stelle anteilig umgerechnet.
     */
    fun offer(point: IndoorResumePoint?, lengthM: Double, isLoop: Boolean): Double? {
        if (point == null || !lengthM.isFinite() || lengthM <= 0) return null
        if (abs(point.routeLengthM - lengthM) > LENGTH_TOLERANCE * lengthM) return null
        val scaled = point.routeDistM * lengthM / point.routeLengthM
        val d = clamp(scaled, lengthM, isLoop)
        return if (d < MIN_RESUME_M) null else d
    }
}
