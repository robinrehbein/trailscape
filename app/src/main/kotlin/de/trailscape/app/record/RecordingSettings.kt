package de.trailscape.app.record

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import de.trailscape.app.data.trailscapePrefs

/**
 * Einstellungen rund um die Aufzeichnung — gelesen und geschrieben ueber
 * dieselben `SharedPreferences` wie der Rest der App (siehe
 * `data/PrefsStores.kt`), bewusst OHNE Umweg ueber das `AppViewModel`: Der
 * [RecordingService] braucht die Werte auf seinem eigenen Thread, und die
 * schlanke Karte „Aufzeichnung" unter Mehr (`ui/more/RecordingCard.kt`)
 * liest/schreibt sie direkt im Composable.
 *
 * Dazu die beiden Handgriffe fuer die **Batterieoptimierung**: Manche
 * Hersteller-Energiesparer raeumen den Standort-Listener eines laufenden
 * Vordergrunddienstes ab — die Aufzeichnung laeuft dann scheinbar weiter,
 * zeichnet aber nichts mehr auf. Die Ausnahme von der Batterieoptimierung ist
 * das offizielle Mittel dagegen; angefragt wird sie einmalig beim Start einer
 * Aufzeichnung (`ui/map/MapScreen.kt`) und jederzeit unter Mehr →
 * Aufzeichnung.
 */

/** Schluessel des Auto-Pause-Schalters (Boolean, Default AN). */
internal const val PREF_AUTO_PAUSE = "trailscape.autopause"

/**
 * Schluessel des Merkers, dass der Batterieoptimierungs-Hinweis beim Start
 * einer Aufzeichnung bereits gezeigt wurde (Boolean). Der Hinweis erscheint
 * hoechstens einmal automatisch — danach nur noch auf Wunsch unter Mehr.
 */
internal const val PREF_BATTERY_NOTICE_SHOWN = "trailscape.batteryNoticeShown"

/** Ob die Auto-Pause eingeschaltet ist (Default AN). */
internal fun autoPauseAktiviert(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_AUTO_PAUSE, true)

/** Schreibt den Auto-Pause-Schalter. */
internal fun setzeAutoPauseAktiviert(context: Context, aktiviert: Boolean) {
    trailscapePrefs(context).edit().putBoolean(PREF_AUTO_PAUSE, aktiviert).apply()
}

/** Ob der einmalige Batterie-Hinweis beim Aufzeichnungsstart schon lief. */
internal fun batterieHinweisGezeigt(context: Context): Boolean =
    trailscapePrefs(context).getBoolean(PREF_BATTERY_NOTICE_SHOWN, false)

/** Merkt den Batterie-Hinweis als gezeigt (egal, wie er beantwortet wurde). */
internal fun merkeBatterieHinweisGezeigt(context: Context) {
    trailscapePrefs(context).edit().putBoolean(PREF_BATTERY_NOTICE_SHOWN, true).apply()
}

/**
 * Ob Trailscape bereits von der Batterieoptimierung ausgenommen ist. `false`
 * auch, wenn sich das nicht feststellen laesst — dann bleibt hoechstens ein
 * ueberfluessiger Hinweis, kein stiller Datenverlust.
 */
internal fun vonBatterieoptimierungAusgenommen(context: Context): Boolean = try {
    context.getSystemService(PowerManager::class.java)
        ?.isIgnoringBatteryOptimizations(context.packageName) == true
} catch (e: Exception) {
    false
}

/**
 * Systemdialog „Soll Trailscape die Batterieoptimierung ignorieren duerfen?".
 * Braucht `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` im Manifest; das
 * `BatteryLife`-Lint ist unterdrueckt, weil genau dieser Weg hier gewollt ist:
 * Ein GPS-Logger ist der dokumentierte Ausnahmefall, fuer den die Ausnahme
 * gedacht ist — ohne sie beenden manche Geraete die Aufzeichnung im
 * Hintergrund.
 */
@SuppressLint("BatteryLife")
internal fun batterieAusnahmeIntent(context: Context): Intent =
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        .setData(Uri.parse("package:${context.packageName}"))
