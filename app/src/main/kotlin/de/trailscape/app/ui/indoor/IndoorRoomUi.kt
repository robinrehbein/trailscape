package de.trailscape.app.ui.indoor

import de.trailscape.app.challenge.ChallengeAvailability
import de.trailscape.core.indoor.catalog.CatalogText

/**
 * Zustände des „Raums für Gruppenfahrten" (docs/raum-protokoll.md,
 * docs/ux/umbau.md): der Abschnitt „Raum" in Indoor → Gemeinsam und die
 * Einblendung während der Fahrt. Reine Daten, damit Bildschirme und Tests sie
 * ohne Netz bauen können; erzeugt werden sie in [IndoorRoomController].
 */

/**
 * Der eigene Server aus Einstellungen → Sync mit eigenem Server.
 *
 * @property host nur der Rechnername zur Anzeige (nie das Token); `null` = keiner eingetragen.
 */
data class RoomServerUi(val host: String?) {
    val configured: Boolean get() = host != null
}

/** Warum kein Raum (mehr) offen ist — ein Satz in der Oberfläche je Grund. */
enum class RoomNotice {
    /** Der Gastgeber hat den Raum beendet. */
    ENDED_BY_HOST,

    /** Die Lebensdauer des Raums ist abgelaufen. */
    ENDED_EXPIRED,

    /** Niemand war mehr verbunden. */
    ENDED_IDLE,

    /** Der Server wurde beendet oder neu gestartet. */
    ENDED_SERVER,

    /** Raum unbekannt oder Einladung ungültig (der Server unterscheidet das absichtlich nicht). */
    DENIED,
    FULL,

    /** Server und App sprechen verschiedene Protokollversionen. */
    VERSION,

    /** Dasselbe Gerät ist über eine andere Verbindung beigetreten. */
    REPLACED,

    /** Zu viele Fehlversuche von dieser Adresse. */
    BLOCKED,

    /** Der Server lehnt die Nachrichten der App ab. */
    PROTOCOL,

    /** Verbindung weg und alle Versuche erfolglos. */
    GAVE_UP,

    /** Anlegen: der Server lehnt das eingetragene Token ab. */
    CREATE_UNAUTHORIZED,

    /** Anlegen: der Server bietet keine Räume an (alte Version oder abgeschaltet). */
    CREATE_DISABLED,

    /** Anlegen: die Höchstzahl der Räume ist erreicht. */
    CREATE_SERVER_FULL,

    /** Anlegen: Server nicht erreichbar. */
    CREATE_NETWORK,

    /** Anlegen: unerwartete Antwort. */
    CREATE_FAILED,
}

/** Die Verbindung zum Raum, solange er offen ist. */
sealed interface RoomConnectionUi {
    data object Online : RoomConnectionUi

    /**
     * Verbindung unterbrochen; die App versucht es von selbst wieder.
     *
     * @property attempt der wievielte Versuch läuft bzw. ansteht.
     * @property retryInS Sekunden bis zum nächsten Versuch; `null` = er läuft gerade.
     */
    data class Reconnecting(val attempt: Int, val retryInS: Int?) : RoomConnectionUi
}

/**
 * Die Strecke des Raums und ob sie auf diesem Gerät bereitliegt — dieselbe
 * Passung wie bei einer Herausforderung ([ChallengeAvailability]).
 *
 * @property key Schlüssel der Streckenwahl (`IndoorRouteOption.key`).
 * @property name Katalogname; `null` = Teststrecke oder unbekannt (dann [fallbackName]).
 * @property fallbackName der Name, den der Raum mitschickt.
 */
data class RoomRouteUi(
    val key: String,
    val name: CatalogText?,
    val fallbackName: String?,
    val lengthKm: Double,
    val availability: ChallengeAvailability,
    val catalogId: String?,
)

/**
 * Ein Teilnehmer in der Liste.
 *
 * @property atStart „Bereit": steht mit geladener Strecke am Start (sein Gerät meldet eine Stelle).
 * @property riding nach dem Start unterwegs.
 * @property distKm gefahrene Strecke nach dem Start, sonst `null`.
 */
data class RoomRiderUi(
    val id: String,
    val name: String,
    val self: Boolean,
    val host: Boolean,
    val atStart: Boolean,
    val riding: Boolean = false,
    val distKm: Double? = null,
)

/** Der Abschnitt „Raum" in Indoor → Gemeinsam. */
sealed interface IndoorRoomUi {
    /** Kein Raum offen; [notice] erklärt das Ende des letzten. */
    data class Closed(val notice: RoomNotice? = null) : IndoorRoomUi

    /**
     * Der Raum wird angelegt oder zum ersten Mal betreten.
     *
     * @property creating `true` = „Raum anlegen" läuft noch (vor der ersten Verbindung).
     * @property attempt Verbindungsversuch (ab 2 sichtbar).
     */
    data class Opening(val creating: Boolean, val serverHost: String, val attempt: Int = 1) : IndoorRoomUi

    /**
     * Im Raum.
     *
     * @property riders alle Teilnehmer, man selbst zuerst.
     * @property countdownS Sekunden bis zum Start; `null` = noch kein Start angesetzt oder schon gestartet.
     * @property started der gemeinsame Start ist vorbei.
     * @property inviteLink der Einladungslink zum Teilen (ohne Gastgeber-Geheimnis).
     * @property selfAtStart man steht selbst am Start (Fahransicht offen).
     */
    data class Open(
        val serverHost: String,
        val host: Boolean,
        val route: RoomRouteUi,
        val riders: List<RoomRiderUi>,
        val maxRiders: Int,
        val connection: RoomConnectionUi,
        val countdownS: Int?,
        val started: Boolean,
        val inviteLink: String,
        val sharePower: Boolean,
        val selfAtStart: Boolean = false,
    ) : IndoorRoomUi
}

/**
 * Ein anderer Fahrer während der Fahrt.
 *
 * @property gapM Abstand in Metern: positiv = vor mir, negativ = hinter mir.
 * @property stale seit über 3 s keine Meldung (Verbindung dieses Fahrers hängt).
 * @property powerW nur, wenn dieser Fahrer seine Leistung zeigt.
 */
data class RoomGapUi(
    val id: String,
    val name: String,
    val gapM: Double,
    val stale: Boolean,
    val powerW: Int? = null,
)

/**
 * Was die Fahransicht vom Raum zeigt (`IndoorRideUi.room`).
 *
 * @property waiting vor dem gemeinsamen Start: Die Fahrt wird gehalten.
 * @property countdownS Sekunden bis zum Start; `null` = noch keiner angesetzt.
 * @property atStart / [total] wie viele am Start stehen (man selbst mitgezählt).
 * @property riders die anderen, nächster zuerst (höchstens [MAX_SHOWN]).
 * @property closed der Raum ist während dieser Fahrt zu Ende gegangen oder verlassen worden;
 *   die Fahrt läuft allein weiter.
 */
data class RoomRideUi(
    val connection: RoomConnectionUi,
    val waiting: Boolean,
    val countdownS: Int?,
    val host: Boolean,
    val atStart: Int,
    val total: Int,
    val riders: List<RoomGapUi>,
    val closed: Boolean = false,
) {
    companion object {
        const val MAX_SHOWN = 3
    }
}
