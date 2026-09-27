package de.trailscape.app.ui.rides

import android.content.Context
import de.trailscape.app.data.trailscapePrefs

/**
 * Die gemerkte Wahl des Teilen-Dialogs — wie `record/RecordingSettings.kt`
 * direkt ueber die gemeinsamen `SharedPreferences` (`data/PrefsStores.kt`),
 * ohne Umweg ueber das `AppViewModel`: Nur der Teilen-Wirt ([RideShareDialog])
 * liest und schreibt sie.
 */

/**
 * Schluessel des Schalters „Start und Ziel ausblenden" im Tour-Bild (Boolean,
 * Default AN). Bewusst AN: Wer ein Bild teilt, denkt selten daran, dass die
 * Form der Strecke die Haustuer zeigt — der Schutz soll nicht erst ein
 * Handgriff sein. Wer die ganze Linie will, schaltet ihn einmal aus.
 */
internal const val PREF_SHARE_HIDE_ENDS = "trailscape.share.hideEnds"

/** Ob das Tour-Bild Start und Ziel ausblendet (Default AN). */
internal fun shareHideEnds(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_SHARE_HIDE_ENDS, true)

/** Schreibt den Schalter „Start und Ziel ausblenden". */
internal fun setShareHideEnds(context: Context, value: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_SHARE_HIDE_ENDS, value).apply()
}
