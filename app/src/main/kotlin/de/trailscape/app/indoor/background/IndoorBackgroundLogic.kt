package de.trailscape.app.indoor.background

import android.content.res.Resources
import de.trailscape.app.R
import de.trailscape.app.indoor.IndoorRuntimeState
import de.trailscape.core.formatDuration
import de.trailscape.core.i18n.AppLanguage
import de.trailscape.core.i18n.formatDistanceKm
import de.trailscape.core.indoor.IndoorPhase

/**
 * Plattformarme Regeln der Indoor-Fahrt im Hintergrund (docs/indoor/hintergrund.md),
 * damit Dienst und ViewModel sie nicht je fuer sich entscheiden.
 */
object IndoorBackgroundLogic {

    /** Android 14 (API 34): ab hier verlangt ein Vordergrunddienst je Typ seine Voraussetzungen. */
    const val SDK_FOREGROUND_TYPE_PREREQUISITES = 34

    /**
     * Ob der Vordergrunddienst vom Typ `connectedDevice` starten darf. Ab
     * Android 14 verlangt das System dafuer eine erteilte Laufzeitberechtigung
     * fuer verbundene Geraete — fuer Trailscape `BLUETOOTH_CONNECT` (die hat,
     * wer einen Trainer gekoppelt hat). Ohne sie wirft `startForeground`; die
     * Fahrt pausiert dann wie frueher beim Verlassen der App (z. B. eine
     * Demo-Fahrt ohne Bluetooth-Freigabe).
     */
    fun canStartService(sdkInt: Int, hasBluetoothConnect: Boolean): Boolean =
        sdkInt < SDK_FOREGROUND_TYPE_PREREQUISITES || hasBluetoothConnect

    /** Was die Benachrichtigung zeigt — ohne Texte, damit es sich ohne Ressourcen pruefen laesst. */
    data class NotificationModel(
        val kind: Kind,
        val powerW: Int?,
        val elapsedS: Int,
        val distanceKm: Double,
        /** Aktion „Weiter" statt „Pause". */
        val showResume: Boolean,
    ) {
        enum class Kind { READY, RIDING, PAUSED, FINISHING }
    }

    fun modelOf(rs: IndoorRuntimeState?): NotificationModel {
        val s = rs?.session
        val kind = when {
            rs == null || rs.finished -> NotificationModel.Kind.FINISHING
            s == null || s.phase == IndoorPhase.READY -> NotificationModel.Kind.READY
            s.phase == IndoorPhase.PAUSED || s.phase == IndoorPhase.AUTO_PAUSED -> NotificationModel.Kind.PAUSED
            s.phase == IndoorPhase.FINISHED -> NotificationModel.Kind.FINISHING
            else -> NotificationModel.Kind.RIDING
        }
        return NotificationModel(
            kind = kind,
            powerW = s?.powerW?.takeIf { kind == NotificationModel.Kind.RIDING },
            elapsedS = s?.elapsedS ?: 0,
            distanceKm = (s?.totals?.distanceM ?: 0.0) / 1000.0,
            // Nur eine harte Pause endet mit „Weiter"; die Auto-Pause endet mit dem naechsten Tritt.
            showResume = s?.phase == IndoorPhase.PAUSED,
        )
    }

    /** Titel der laufenden Benachrichtigung. */
    fun title(res: Resources, routeName: String, m: NotificationModel): String = res.getString(
        when (m.kind) {
            NotificationModel.Kind.READY -> R.string.idee05_notification_title_ready
            NotificationModel.Kind.RIDING -> R.string.idee05_notification_title_riding
            NotificationModel.Kind.PAUSED -> R.string.idee05_notification_title_paused
            NotificationModel.Kind.FINISHING -> R.string.idee05_notification_title_finishing
        },
        routeName,
    )

    /** Zeile „180 W · 0:42:10 · 12,3 km". */
    fun text(res: Resources, language: AppLanguage, m: NotificationModel): String = res.getString(
        R.string.idee05_notification_text,
        m.powerW?.let { res.getString(R.string.idee05_notification_power, it) } ?: res.getString(R.string.idee05_notification_power_none),
        formatDuration(m.elapsedS.coerceAtLeast(0)),
        res.getString(R.string.idee05_notification_distance, formatDistanceKm(m.distanceKm, language)),
    )
}
